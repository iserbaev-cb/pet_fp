package test

import java.util.concurrent.atomic.AtomicLong

import com.iserba.fp._
import com.iserba.fp.algebra._
import com.iserba.fp.utils.Free
import test.TestImpl._

import scala.collection.mutable
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.language.{higherKinds, implicitConversions}

object Test extends App {
  val testConnection: IO[Connection] = parFIO.now(new TestConnection)
  def runServer: ParF[IndexedSeq[Resp]] = {
    new Server[ParF] {
      override def convert: Req => Resp = TestImpl.convert
      override def conn: ParF[Connection] = Free.run(testConnection)
    }.run().runLog
  }
  val client1 = new Client[ParF] {
    override def conn: ParF[Connection] = Free.run(testConnection)
  }
  val client2 = new Client[ParF] {
    override def conn: ParF[Connection] = Free.run(testConnection)
  }
  def makeReq(client: Client[ParF]): ParF[IndexedSeq[Resp]] = {
    client.call(testRequest).runLog
  }
  val serverRun = runServer
  val req1 = makeReq(client1)
  val req2 = makeReq(client2)
  val req3 = makeReq(client1)
  val req4 = makeReq(client2)

  Await.result(for {
    _ <- serverRun
    _ <- req1
    _ <- req2
    _ <- req3
    _ <- req4
  } yield {}, Duration.Inf)

}

object TestImpl {
  case class RequestImpl[A](entity: Option[A]) extends Request[Option, A]
  case class ResponseImpl[A](body: Option[A]) extends Response[Option, A]
  case class TestModel(id: Option[Long], info: String) extends Model

  def ts = System.currentTimeMillis()
  private val idAccumulator = new AtomicLong(0L)
  def testModel(id: Long = idAccumulator.getAndIncrement()): Model = TestModel(Some(id), "")
  def eventGen: Event = Event(ts = ts, model = testModel())
  def eventsF = List(eventGen)
  def testRequest: Req = RequestImpl(Some(eventGen))

  class TestConnection extends Connection {
    private var requests = List[Req]()
    private val responses = mutable.Map[Req,Resp]()

    def getRequest = {
      Thread.sleep(1000)
      requests.headOption.map { r =>
        requests = if (requests.isEmpty) Nil else requests.tail
        r
      }
    }
    def makeRequest(req: Req): Resp = {
      def tryToGet(req: Req): Resp = {
        responses.getOrElse(req, {
          Thread.sleep(1000)
          tryToGet(req)
        })
      }
      val nl = req :: requests
      requests = nl
      tryToGet(req)
    }

    def addResponse(resp: Resp, req: Req): Resp = {
      responses.addOne(req,resp)
      resp
    }
  }

  def convert: Req => Resp = req =>
    ResponseImpl(req.entity.map{ event =>
      event.copy(model = TestModel(event.model.id, "updated model"))
    })
}