package com.iserba.fp.free

import java.util.UUID

import com.iserba.fp.free.algebra._
import com.iserba.fp.free.interpreters._
import com.iserba.fp.model._
import com.iserba.fp.utils.Free.{Translate, freeMonad, ~>}
import com.iserba.fp.utils.StreamProcessHelper._
import com.iserba.fp.utils.{Free, Monad}
import com.iserba.fp._

import scala.concurrent.{ExecutionContext, Future}
import scala.language.{higherKinds, implicitConversions}

object algebra {
  trait Converter[I,O] {
    def convert: I => O
  }

  sealed trait ServerAlg[T]
  case class RunServer() extends ServerAlg[ServerChannel]
  case class ServerChannel(id: UUID, channel: RequestResponseChannel) extends ServerAlg[(UUID, RequestResponseChannel)]
  type Server[T] = Free[ServerAlg, T]
  def runServer(): Server[ServerChannel] =
    Free.liftF(RunServer())
  def serverChannel(id: UUID): Server[(UUID, RequestResponseChannel)] =
    Free.liftF(ServerChannel(id, createServerChannel(dummyLogic)))

  sealed trait ConnectionAlg[T]
  case class ClientConnect(serverId: UUID) extends ConnectionAlg[RequestResponseChannel]
  case class RegisterServer(serverId: UUID) extends ConnectionAlg[ServerChannel]
  case class ServerConnect(serverId: UUID, channel: RequestResponseChannel) extends ConnectionAlg[(UUID, RequestResponseChannel)]
  type Connection[T] = Free[ConnectionAlg, T]
  def createClientConnection(serverId: UUID): Connection[RequestResponseChannel] =
    Free.liftF(ClientConnect(serverId))
  def registerServer(): Connection[ServerChannel] =
    Free.liftF(RegisterServer(UUID.randomUUID()))
  def connectServer(serverId: UUID, channel: RequestResponseChannel): Connection[(UUID, RequestResponseChannel)] =
    Free.liftF(ServerConnect(serverId,channel))

  sealed trait ClientAlg[T]
  case class RunClient(serverId: UUID) extends ClientAlg[RequestResponseChannel]
  type Client[T] = Free[ClientAlg, T]
  def runClient(serverId: UUID): Client[RequestResponseChannel] =
    Free.liftF(RunClient(serverId))
}

object interpreters {
  def serverToConnectionInterpreter: Translate[ServerAlg, Connection] = new (ServerAlg ~> Connection) {
    override def apply[A](f: ServerAlg[A]): Connection[A] = f match {
      case RunServer() =>
        registerServer()
      case ServerChannel(id, channel) =>
        connectServer(id, channel)
    }
  }

  class AlgInterpreter[Alg[_]](implicit monad: Monad[({type f[a] = Free[Alg, a]})#f]) {
    type FreeAlg[A] = Free[Alg,A]
    def translate(serverConnections: Map[UUID, RequestResponseChannel] = Map())
    : Translate[ConnectionAlg, FreeAlg] =
      new (ConnectionAlg ~> FreeAlg) {
        override def apply[A](f: ConnectionAlg[A]): FreeAlg[A] = f match {
          case ClientConnect(serverId) =>
            monad.unit(serverConnections(serverId))
          case RegisterServer(serverId) =>
            monad.unit(ServerChannel(serverId, createServerChannel(dummyLogic)))
          case ServerConnect(serverId, channel) =>
            monad.unit(serverId -> channel)
        }
      }
  }

  def clientToConnectionInterpreter: Translate[ClientAlg, Connection] = new (ClientAlg ~> Connection) {
    override def apply[A](f: ClientAlg[A]): Connection[A] = f match {
      case RunClient(serverId) =>
        createClientConnection(serverId)
    }
  }
}

object compilers {
  implicit val connFreeMonad: Monad[({type f[a] = Free[ConnectionAlg, a]})#f] =
    freeMonad[ConnectionAlg]
  implicit val serverFreeMonad: Monad[({type f[a] = Free[ServerAlg, a]})#f] =
    freeMonad[ServerAlg]
  private val connectionToFutureInterpreter = new AlgInterpreter[Future]
  private val connectionToServerInterpreter = new AlgInterpreter[ServerAlg]

  def runClientFree(serverId: UUID)
                   (implicit
                    serverConnections: Map[UUID, RequestResponseChannel],
                    ec: ExecutionContext): Future[RequestResponseChannel] =
    runClient(serverId)
      .foldMap(clientToConnectionInterpreter)
      .foldMap(connectionToFutureInterpreter.translate(serverConnections))
      .runFree

  def runServerFree()(implicit ec: ExecutionContext): Future[(UUID, RequestResponseChannel)] =
    runServer()
      .foldMap(serverToConnectionInterpreter)
      .foldMap(connectionToServerInterpreter.translate())
      .foldMap(serverToConnectionInterpreter)
      .foldMap(connectionToFutureInterpreter.translate())
      .runFree.map(t => t.id -> t.channel)

  def makeRequest(request: Request, channel: RequestResponseChannel): Future[model.Response] = // TODO interpreter
    emit(request)
      .through(channel)
      .runStreamProcess
      .map(_.head)
      .runFree
}
