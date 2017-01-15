package com.digitaltangible.playguard

import java.util.NoSuchElementException

import akka.actor.ActorSystem
import com.digitaltangible.tokenbucket.{Clock, CurrentTimeClock, TokenBucketGroup}
import play.api.libs.concurrent.Execution.Implicits._
import play.api.mvc.{ActionBuilder, _}
import play.api.{Configuration, Logger}

import scala.concurrent.Future
import scala.util.control.NonFatal


object KeyRateLimitAction {

  /**
    * Creates an ActionBuilder which holds a RateLimiter with a bucket for each key.
    * Every request consumes a token. If no tokens remain, the request is rejected.
    *
    * @param rl
    * @param key
    * @param rejectResponse
    * @return
    */
  def apply(rl: RateLimiter)(rejectResponse: Request[_] => Result)(key: Any): RateLimitActionFilter[Request] with ActionBuilder[Request] =
    new RateLimitActionFilter[Request](rl)(rejectResponse)((_: Request[_]) => key) with ActionBuilder[Request]
}


object IpRateLimitAction {

  /**
    * Creates an ActionBuilder which holds a RateLimiter with a bucket for each IP address.
    * Every request consumes a token. If no tokens remain, the request is rejected.
    *
    * @param conf
    * @param rl
    * @param rejectResponse
    * @return
    */
  def apply(rl: RateLimiter)(rejectResponse: Request[_] => Result)(implicit conf: Configuration): RateLimitActionFilter[Request] with ActionBuilder[Request] =
    new RateLimitActionFilter[Request](rl)(rejectResponse)(getClientIp) with ActionBuilder[Request]
}


/**
  * ActionFilter to be used on any Request type. Useful if you want to use content from a wrapped request, e.g. User ID
  *
  * @param rl
  * @param rejectResponse
  * @param f
  * @tparam R
  * @return
  */
class RateLimitActionFilter[R[_] <: Request[_]](rl: RateLimiter)(rejectResponse: R[_] => Result)(f: R[_] => Any) extends ActionFilter[R] {
  def filter[A](request: R[A]): Future[Option[Result]] = {
    rl.consumeAndCheck(f(request), request.path).map { res =>
      if (res) None
      else Some(rejectResponse(request))
    }
  }
}


object FailureRateLimitAction {

  /**
    * Creates an Action which holds a RateLimiter with a bucket for each IP address.
    * Tokens are consumed only by failures. If no tokens remain, the request is rejected.
    *
    * @param frl
    * @param rejectResponse
    * @param errorCodes
    * @param conf
    * @return
    */
  def apply(frl: RateLimiter)(rejectResponse: Request[_] => Result,
                              errorCodes: Seq[Int] = 400 to 499)(implicit conf: Configuration) =

    new FailureRateLimitFunction[Request](frl)(rejectResponse)(getClientIp, r => !(errorCodes contains r.header.status)) with ActionBuilder[Request]
}

/**
  * ActionFunction to be used on any Request type. Useful if you want to use content from a wrapped request, e.g. User ID
  */
class FailureRateLimitFunction[R[_] <: Request[_]](frl: RateLimiter)(rejectResponse: R[_] => Result)(keyFromRequest: R[_] => Any, resultCheck: Result => Boolean)(implicit conf: Configuration) extends ActionFunction[R, R] {

  def invokeBlock[A](request: R[A], block: (R[A]) => Future[Result]): Future[Result] = {

    val key = keyFromRequest(request)

    (for {
      ok <- frl.check(key, request.path)
      if ok
      res <- block(request)
      _ = if (!resultCheck(res)) frl.consume(key)
    } yield res).recover {
      case ex: NoSuchElementException =>
        rejectResponse(request)
    }
  }
}

/**
  * Holds a TokenBucketGroup for rate limiting. You can share an instance if you want different Actions to use the same TokenBucketGroup.
  *
  * @param size
  * @param rate
  * @param logPrefix
  * @param clock
  * @param system
  */
class RateLimiter(size: Int, rate: Float, logPrefix: String = "", clock: Clock = CurrentTimeClock)(implicit system: ActorSystem) {

  private val logger = Logger(this.getClass)

  private lazy val tbActorRef = TokenBucketGroup.create(size, rate, clock)

  /**
    * Checks if the bucket for the given key has at least one token left.
    * If available, the token is consumed.
    *
    * @param key bucket key
    * @return
    */
  def consumeAndCheck(key: Any, path: String): Future[Boolean] = consumeAndCheck(key, path, 1, _ >= 0)

  /**
    * Checks if the bucket for the given key has at least one token left.
    *
    * @param key bucket key
    * @return
    */
  def check(key: Any, path: String): Future[Boolean] = consumeAndCheck(key, path, 0, _ > 0)


  private def consumeAndCheck(key: Any, path: String, amount: Int, check: Int => Boolean): Future[Boolean] = {
    TokenBucketGroup.consume(tbActorRef, key, amount).map { remaining =>
      if (check(remaining)) {
        if (remaining < size.toFloat / 2) logger.warn(s"$logPrefix rate limit for $key below 50%: $remaining, path: $path")
        true
      } else {
        logger.error(s"$logPrefix rate limit for $key exceeded, path: $path")
        false
      }
    } recover {
      case NonFatal(ex) =>
        logger.error(s"$logPrefix rate limiter failed", ex)
        true // let pass in case of internal failure
    }
  }

  /**
    * Consumes one token for the given key
    *
    * @param key
    * @return
    */
  def consume(key: Any): Future[Int] = TokenBucketGroup.consume(tbActorRef, key, 1)
}
