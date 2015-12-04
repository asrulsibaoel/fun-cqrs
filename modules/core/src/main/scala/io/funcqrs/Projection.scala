package io.funcqrs

import io.funcqrs.Projection._

import scala.concurrent.Future
trait Projection {

  def handleEvent: HandleEvent

  final def onEvent(evt: DomainEvent): Future[Unit] = {
    if (handleEvent.isDefinedAt(evt)) {
      handleEvent(evt)
    } else {
      Future.successful(())
    }
  }

  /** Builds a [[AndThenProjection]] composed of this Projection and the passed Projection.
    *
    * [[DomainEvent]]s will be send to both projections. One after the other starting by this followed by the passed Projection.
    *
    * NOTE: In the occurrence of any failure on any of the underling Projections, this Projection may be replayed,
    * therefore idempotent operations are recommended.
    */
  def andThen(projection: Projection): AndThenProjection = new AndThenProjection(this, projection)

  /** Builds a [[OrElseProjection]]composed of this Projection and the passed Projection.
    *
    * If this Projection is defined for a given incoming [[DomainEvent]], then this Projection will be applied,
    * otherwise we fallback to the passed Projection.
    */
  def orElse(fallbackProjection: Projection): OrElseProjection = new OrElseProjection(this, fallbackProjection)

  /** Builds a [[AndThenProjection]]composed of this Projection and the passed TypedProjection.
    *
    * [[DomainEvent]]s will be send to both projections. One after the other starting by this followed by the passed Projection.
    *
    * NOTE:
    * - In the occurrence of any failure on any of the underling Projections, this Projection may be replayed,
    * therefore idempotent operations are recommended.
    * - the TypedProjection will be wrapped by the 'default' Projection which is typed to Unit.
    */
  def andThen[B](projection: TypedProjection[B]): AndThenProjection = new AndThenProjection(this, projection.asProjection)

  /** Builds a [[OrElseProjection]]composed of this Projection and the passed TypedProjection.
    *
    * If this Projection is defined for a given incoming [[DomainEvent]], then this Projection will be applied,
    * otherwise we fallback to the passed TypedProjection.
    *
    * NOTE: the TypedProjection will be wrapped by the 'default' Projection which is typed to Unit.
    */
  def orElse[B](fallbackProjection: TypedProjection[B]): OrElseProjection = new OrElseProjection(this, fallbackProjection.asProjection)
}

trait TypedProjection[A] {
  self =>

  def handleEvent: PartialFunction[DomainEvent, Future[A]]

  def asProjection: Projection = new Projection {
    import scala.concurrent.ExecutionContext.Implicits.global
    def handleEvent: HandleEvent = {
      case any if self.handleEvent.isDefinedAt(any) =>
        self.handleEvent(any).map(_ => Unit)
    }
  }

  /** Builds a [[AndThenProjection]]composed of this TypedProjection and the passed Projection.
    *
    * [[DomainEvent]]s will be send to both projections. One after the other starting by this followed by the passed Projection.
    *
    * NOTE:
    * - In the occurrence of any failure on any of the underling Projections, this Projection may be replayed,
    * therefore idempotent operations are recommended.
    * - the TypedProjection will be wrapped by the 'default' Projection which is typed to Unit.
    */
  def andThen(projection: Projection) = new AndThenProjection(this.asProjection, projection)

  /** Builds a [[OrElseProjection]]composed of this TypedProjection and the passed Projection.
    *
    * If this Projection is defined for a given incoming [[DomainEvent]], then this Projection will be applied,
    * otherwise we fallback to the passed TypedProjection.
    *
    * NOTE: the TypedProjection will be wrapped by the 'default' Projection which is typed to Unit.
    */
  def orElse(fallbackProjection: Projection) = new OrElseProjection(this.asProjection, fallbackProjection)

  /** Builds a [[AndThenProjection]]composed of this TypedProjection and the passed TypedProjection.
    *
    * [[DomainEvent]]s will be send to both projections. One after the other starting by this followed by the passed Projection.
    *
    * NOTE:
    * - In the occurrence of any failure on any of the underling Projections, this Projection may be replayed,
    * therefore idempotent operations are recommended.
    * - both TypedProjections will be wrapped by the 'default' Projection which is typed to Unit.
    */
  def andThen[B](projection: TypedProjection[B]) = new AndThenProjection(this.asProjection, projection.asProjection)

  /** Builds a [[OrElseProjection]]composed of this TypedProjection and the passed Projection.
    *
    * If this Projection is defined for a given incoming [[DomainEvent]], then this Projection will be applied,
    * otherwise we fallback to the passed TypedProjection.
    *
    * NOTE: both TypedProjections will be wrapped by the 'default' Projection which is typed to Unit.
    */
  def orElse[B](fallbackProjection: TypedProjection[B]) = new OrElseProjection(this.asProjection, fallbackProjection.asProjection)
}

object Projection {

  /** Projection with empty domain */
  def empty = new Projection {
    def handleEvent: HandleEvent = PartialFunction.empty
  }

  /** A [[Projection]] composed of two other Projections to each [[DomainEvent]] will be sent.
    *
    * Note that the second Projection is only applied once the first is completed successfully.
    *
    * In the occurrence of any failure on any of the underling Projections, this Projection may be replayed,
    * therefore idempotent operations are recommended.
    *
    * If none of the underlying Projections is defined for a given DomainEvent,
    * then this Projection is considered to be not defined for this specific DomainEvent.
    * As such a [[AndThenProjection]] can be combined with a [[OrElseProjection]].
    *
    * For example:
    * {{{
    * val projection1 : Projection = ...
    * val projection2 : Projection = ...
    * val projection3 : Projection = ...
    *
    * val finalProjection = (projection1 andThen projection2) orElse projection3
    *
    * finalProjection.onEvent(SomeEvent("abc"))
    * // if SomeEvent("abc") is not defined for projection1 nor for projection2, projection3 will be applied
    * }}}
    *
    */
  private[funcqrs] class AndThenProjection(firstProj: Projection, secondProj: Projection) extends ComposedProjection(firstProj, secondProj) with Projection {

    import scala.concurrent.ExecutionContext.Implicits.global

    val projections = Seq(firstProj, secondProj)

    def handleEvent: HandleEvent = {
      // note that we only broadcast if at least one of the underlying
      // projections is defined for the incoming event
      // as such we make it possible to compose using orElse
      case domainEvent if composedHandleEvent.isDefinedAt(domainEvent) =>
        // send event to all projections
        firstProj.onEvent(domainEvent).flatMap { _ =>
          secondProj.onEvent(domainEvent)
        }
    }
  }

  /** A [[Projection]] composed of two other Projections.
    *
    * Its `receiveEvent` is defined in terms of the `receiveEvent` method form the first Projection
    * with fallback to the `receiveEvent` method of the second Projection.
    *
    * As such the second Projection is only applied if the first Projection is not defined
    * for the given incoming [[DomainEvent]]
    *
    */
  private[funcqrs] class OrElseProjection(firstProj: Projection, secondProj: Projection) extends ComposedProjection(firstProj, secondProj) with Projection {
    def handleEvent = composedHandleEvent
  }

  private[funcqrs] class ComposedProjection(firstProj: Projection, secondProj: Projection) {
    // compose underlying receiveEvents PartialFunction in order
    // to decide if this Projection is defined for given incoming DomainEvent
    private[funcqrs] def composedHandleEvent = firstProj.handleEvent orElse secondProj.handleEvent
  }

}