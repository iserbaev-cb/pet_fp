package fs2ws.impl

import java.util.UUID

import cats.Monad
import cats.effect.concurrent.Ref
import cats.effect.{Concurrent, Sync}
import fs2ws.Clients
import fs2ws.Domain.{AuthFailResp, AuthReq, AuthSuccessResp, Message, SubscribeTables, UnsubscribeTables, _}

import scala.collection.mutable.ListBuffer

object State {
  case class Client[F[_]: Monad: Concurrent](id: UUID = UUID.randomUUID(),
                                             username: Option[String] = None,
                                             userType: Option[String] = None,
                                             subscribed: Boolean = false) {
    private val msgQueue = ListBuffer[Message]() // TODO
    def add(message: Message): Unit = // TODO
    {
      println(s"Add $message")
      msgQueue.append(message)
    }
    def take: List[Message] = // TODO
    {
      println(s"Take ${msgQueue.mkString("\n")}")
      val result = msgQueue.toList
      msgQueue.clear()
      result
    }

    def privileged: Boolean =
      userType.contains(UserType.ADMIN)
    def updateState(message: Message): Client[F] =
      message match {
        case _: SubscribeTables =>
          copy(subscribed = true)
        case _: UnsubscribeTables =>
          copy(subscribed = false)
        case AuthReq(un,_,_) =>
          copy(username = Some(un))
        case AuthSuccessResp(user_type, _) =>
          copy(userType = Some(user_type))
        case _: AuthFailResp =>
          Client(id)
        case _ =>
          this
      }
  }
  import cats.implicits._
  case class ConnectedClients[F[_]: Sync](ref: Ref[F, Map[UUID, Client[F]]]) extends Clients[F] {
    def register(state: Client[F]): F[Client[F]] =
      ref.modify { oldClients =>
        (oldClients + (state.id -> state),state)
      }
    def unregister(c: Client[F]): F[Unit] =
      ref.update { old =>
        old - c.id
      }
    def broadcast(message: Message, filterF: Client[F] => Boolean): F[Unit] = {
      ref.update { old =>
        old
          .filter{ case (_, client) =>
            filterF(client)}
          .map{ case (uuid, client) =>
            client.add(message)
            (uuid, client)
          }
      }
    }
    def updateClients(client: Client[F], message: Message): F[(Message, Client[F])] =
      get(client.id)
        .flatMap {
          case Some(refClient) =>
            update(refClient.updateState(message))
              .map(updatedClient => (message, updatedClient))
          case None =>
              update(client.updateState(message))
                .map(updatedClient => (message, updatedClient))
        }

    private def get(id: UUID): F[Option[Client[F]]] =
      ref.get.map(_.get(id))
    private def update(toUpdate: Client[F]): F[Client[F]] =
      ref.modify{old =>
        val updatedClient = old.get(toUpdate.id).map(_ => toUpdate)
        val updatedClients = updatedClient
          .map(cl => old + (toUpdate.id -> cl))
          .getOrElse(old)
        (updatedClients, toUpdate)
      }
  }
  object ConnectedClients {
    def create[F[_]: Sync]: F[ConnectedClients[F]] =
      Ref[F]
        .of(Map.empty[UUID, Client[F]])
        .map(ref => new ConnectedClients[F](ref))
  }

}
