package lila.ws

import akka.stream.scaladsl._
import io.lettuce.core._
import io.lettuce.core.pubsub._
import play.api.Logger
import scala.concurrent.{ Future, Promise }

import ipc._

final class Lila(redisUri: RedisURI) {

  import Lila._

  private val logger = Logger(getClass)
  private val redis = RedisClient create redisUri

  private var connections = Map.empty[ChanIn, Connection]

  def pubsub[Out](chanIn: ChanIn, chanOut: ChanOut)(collect: PartialFunction[LilaOut, Out]) = {

    val connIn = redis.connectPubSub()
    val connOut = redis.connectPubSub()

    def send(in: LilaIn): Unit = {
      val msg = in.write
      val timer = Monitor.redisPublishTime.start()
      connIn.async.publish(chanIn, msg).thenRun { timer.stop _ }
      Monitor.redis.in(chanIn, msg.takeWhile(' '.!=))
    }

    val init: SourceQueueWithComplete[Out] => Future[Unit] = queue => {

      connOut.addListener(new RedisPubSubAdapter[ChanOut, String] {
        override def message(channel: ChanOut, msg: String): Unit = {
          Monitor.redis.out(channel, msg.takeWhile(' '.!=))
          LilaOut read msg match {
            case Some(out) => collect lift out match {
              case Some(typed) => queue offer typed
              case None => logger.warn(s"Received $out on wrong channel: $chanOut")
            }
            case None => logger.warn(s"Unhandled $channel LilaOut: $msg")
          }
        }
      })

      val promise = Promise[Unit]
      connOut.async.subscribe(chanOut) thenRun { () =>
        send(LilaIn.WsBoot)
        promise.success(())
      }
      promise.future
    }

    val sink = Sink foreach send

    val close = () => {
      connIn.close()
      connOut.close()
    }
    connections = connections + (chanIn -> new Connection(send, close))

    (init, sink)
  }

  def closeAll: Unit = {
    connections.foreachEntry {
      case (_, conn) => conn.close()
    }
    connections = Map.empty
  }

  def directSend(chan: ChanIn)(in: LilaIn): Unit =
    connections.get(chan).foreach(_ send in)
}

private object Lila {

  type ChanIn = String
  type ChanOut = String

  final class Connection(val send: LilaIn => Unit, val close: () => Unit)
}
