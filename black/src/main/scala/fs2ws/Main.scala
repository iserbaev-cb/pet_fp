package fs2ws

import cats.effect.{ConcurrentEffect, ContextShift, ExitCode, IO, IOApp, Timer}
import com.typesafe.scalalogging.Logger
import fs2ws.Domain.{User, UserType}
import fs2ws.impl.State.ConnectedClients
import fs2ws.impl._
import cats.syntax.all._
import fs2ws.impl.doobie.{
  DoobieService,
  TableReader,
  TableWriter,
  UserReader,
  UserWriter
}

object Main extends IOApp {
  implicit val ce:   ConcurrentEffect[IO] = IO.ioConcurrentEffect
  implicit val conf: Conf[IO]             = new ConfImpl
  implicit val clients: Clients[IO] =
    ConnectedClients.create[IO].unsafeRunSync()
  override def run(args: List[String]): IO[ExitCode] =
    Starter.start()

}

object Starter {
  private val logger = Logger("Main")
  def start[F[_]: ConcurrentEffect: ContextShift: Timer: Conf: Clients]()
    : F[ExitCode] = {
    implicit val db = new DoobieService[F]
    implicit val userReader:     UserReader[F]     = new UserReader[F]
    implicit val userWriter:     UserWriter[F]     = new UserWriter[F]
    implicit val tableReader:    TableReader[F]    = new TableReader[F]
    implicit val tableWriter:    TableWriter[F]    = new TableWriter[F]
    implicit val messageService: MessageService[F] = new MessageServiceImpl[F]
    (for {
      _ <- userWriter.add(
        -1,
        User(Option(0L), "admin", "admin", UserType.ADMIN)
      )
      _ <- userWriter.add(0, User(Option(1L), "un", "upwd", UserType.USER))
      _ <- new ServerImpl[F](
        Http4sWebsocketServer.start(_)
      ).start()
    } yield {
      ExitCode.Success
    }).handleErrorWith(
      ex => {
        logger.info(s"Server stopped with error ${ex.getLocalizedMessage}")
        ExitCode.Error
      }.pure[F]
    )
  }
}
