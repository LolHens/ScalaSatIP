package org.lolhens.satip.rtsp

import java.net.InetSocketAddress

import akka.actor.{Actor, ActorRef, ActorRefFactory, Cancellable, Props, Stash, Terminated}
import akka.io.{IO, Tcp}
import akka.pattern.ask
import akka.routing.{BroadcastRoutingLogic, Router}
import akka.util.Timeout
import org.lolhens.satip.rtsp.RtspActor._
import org.lolhens.satip.rtsp.data.RtspVersion

import scala.concurrent.duration._
import scala.language.postfixOps

/**
  * Created by pierr on 27.03.2017.
  */
class RtspActor extends Actor with Stash {
  override def receive: Receive = {
    case connect@Connect(socketAddress, multicast) =>
      val listener = sender()

      IO(Tcp)(context.system) ! Tcp.Connect(socketAddress)

      context become {
        case Tcp.Connected(remoteAddress, localAddress) =>
          val tcpConnection = sender()
          val connection = RtspConnectionActor.actor(tcpConnection)

          listener tell(Connected(remoteAddress, localAddress), connection)
          tcpConnection ! Tcp.Register(connection, useResumeWriting = false)

          unstashAll()
          context.unbecome()

        case Tcp.CommandFailed(_: Tcp.Connect) =>
          listener ! CommandFailed(connect)

          unstashAll()
          context.unbecome()

        case _ => stash()
      }
  }
}

object RtspActor {
  val props: Props = Props[RtspActor]

  def actor(implicit actorRefFactory: ActorRefFactory): ActorRef =
    actorRefFactory.actorOf(props, "RtspManager")

  trait Command

  trait Event

  case class Connect(socketAddress: InetSocketAddress, multicast: Boolean = false) extends Command

  case class CommandFailed(command: Command) extends Event

  case class Connected(remoteAddress: InetSocketAddress, localAddress: InetSocketAddress) extends Event

  case class Register(actorRef: ActorRef) extends Command

  case class Unregister(actorRef: ActorRef) extends Command

  case class Received(response: RtspResponse) extends Event

  case class Write(request: RtspRequest) extends Command

  private case object Ack extends Event with Tcp.Event

  private case object KeepAlive extends Event

  type ConnectionClosed = Tcp.ConnectionClosed

  class RtspConnectionActor(tcpConnection: ActorRef) extends Actor with Stash {
    var eventRouter = Router(BroadcastRoutingLogic())

    var cSeq = 1

    val keepAliveTimer: Cancellable =
      context.system.scheduler.schedule(0 millis, 10000 millis, self, KeepAlive)(context.system.dispatcher)

    def handleClosed(closed: ConnectionClosed): Unit = {
      eventRouter.route(closed, self)
      context stop self
      keepAliveTimer.cancel()
    }

    override def receive: Receive = {
      case Register(ref) =>
        context watch ref
        eventRouter = eventRouter.addRoutee(ref)

      case Unregister(ref) =>
        context unwatch ref
        eventRouter = eventRouter.removeRoutee(ref)

      case Terminated(ref) =>
        eventRouter = eventRouter.removeRoutee(ref)

      case write@Write(request) =>
        val listener = sender()

        tcpConnection ! Tcp.Write({
          val data = request.toByteString
          data
        }, Ack)

        context.become {
          case Ack =>
            unstashAll()
            context.become {
              case Tcp.Received(data) =>
                //TODO: read until \r\n then read entity
                listener ! Received {
                  val response = RtspResponse.fromByteString(data)
                  response
                }

                unstashAll()
                context.unbecome()

              case closed: ConnectionClosed =>
                handleClosed(closed)

              case _ => stash()
            }

          case Tcp.CommandFailed(_: Tcp.Write) =>
            listener ! CommandFailed(write)
            println("write failed")

          case closed: ConnectionClosed =>
            println("closed?")
            handleClosed(closed)

          case _ => stash()
        }

      case KeepAlive =>
        implicit val rtspVersion = RtspVersion(1, 0)
        val options = RtspRequest.options(s"rtsp://${"10.1.2.6"}:554/" /*stream=0"*/ , cSeq = 1, List(
          RtspHeaderField.Accept("application/sdp") //,
          //RtspHeaderField.Session("0")
        ))

        implicit val timeout = Timeout(1 second)
        self ? Write(options)

      case closed: ConnectionClosed =>
        handleClosed(closed)
    }

    override def postStop(): Unit = {
      keepAliveTimer.cancel()
    }
  }

  object RtspConnectionActor {
    def props(tcpConnection: ActorRef): Props = Props(classOf[RtspConnectionActor], tcpConnection)

    def actor(tcpConnection: ActorRef)(implicit actorRefFactory: ActorRefFactory): ActorRef =
      actorRefFactory.actorOf(props(tcpConnection), "RtspConnection")
  }

}