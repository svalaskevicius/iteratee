package io.iteratee

import algebra.Monoid
import cats.{ Applicative, Comonad, FlatMap, Functor, Id, Monad, MonoidK, Show }
import cats.arrow.NaturalTransformation
import cats.functor.Contravariant

/**
 * An iteratee processes a stream of elements of type `E` and may produce a value of type `F[A]`.
 *
 * @see [[Step]]
 *
 * @tparam F A type constructor representing a context for effects
 * @tparam E The type of the input data
 * @tparam A The type of the calculated result
 */
sealed class Iteratee[E, F[_], A] private(final val step: F[Step[E, F, A]]) extends Serializable {
  /**
   * Collapse this iteratee to an effectful value using the provided functions.
   */
  final def foldWith[Z](folder: StepFolder[E, F, A, Z])(implicit F: Functor[F]): F[Z] =
    F.map(step)(_.foldWith(folder))

  /**
   * Run this iteratee and close the stream so that it must produce an effectful value.
   *
   * @note A well-behaved iteratee will always be in the completed state after processing an
   *       end-of-stream [[Input]] value.
   */
  final def run(implicit F: Monad[F]): F[A] =
    F.map(feed(Enumerator.enumEnd[E, F]).step)(_.unsafeValue)

  final def flatMap[B](f: A => Iteratee[E, F, B])(implicit F: Monad[F]): Iteratee[E, F, B] = {
    def through(it: Iteratee[E, F, A]): Iteratee[E, F, B] = Iteratee.iteratee(
      F.flatMap(it.step)(
        _.foldWith[F[Step[E, F, B]]](
          new StepFolder[E, F, A, F[Step[E, F, B]]] {
            def onCont(k: Input[E] => Iteratee[E, F, A]): F[Step[E, F, B]] =
              F.pure(Step.cont(u => through(k(u))))
            def onDone(value: A, remainder: Input[E]): F[Step[E, F, B]] =
              if (remainder.isEmpty) f(value).step else F.flatMap(f(value).step)(
                _.foldWith(
                  new StepFolder[E, F, B, F[Step[E, F, B]]] {
                    def onCont(ff: Input[E] => Iteratee[E, F, B]): F[Step[E, F, B]] =
                      ff(remainder).step
                    def onDone(aa: B, r: Input[E]): F[Step[E, F, B]] =
                      F.pure(Step.done[E, F, B](aa, remainder))
                  }
                )
              )
            }
          )
        )
      )

    through(this)
  }

  def map[B](f: A => B)(implicit F: Monad[F]): Iteratee[E, F, B] =
    flatMap(a => Step.done[E, F, B](f(a), Input.empty).pointI)

  def contramap[EE](f: EE => E)(implicit F: Monad[F]): Iteratee[EE, F, A] = {
    def step(s: Step[E, F, A]): Iteratee[EE, F, A] = s.foldWith(
      new StepFolder[E, F, A, Iteratee[EE, F, A]] {
        def onCont(k: Input[E] => Iteratee[E, F, A]): Iteratee[EE, F, A] =
          Iteratee.cont((in: Input[EE]) => k(in.map(i => f(i))).advance(step))
        def onDone(value: A, remainder: Input[E]): Iteratee[EE, F, A] =
          Iteratee.done(value, if (remainder.isEnd) Input.end else Input.empty)
      }
    )

    this.advance(step)
  }

  /**
   * Advance this iteratee using a function that transforms a step into an iteratee.
   *
   * @param f An enumerator-like function that may change the types of the input and result.
   */
  final def advance[E2, A2](f: Step[E, F, A] => Iteratee[E2, F, A2])(implicit
    F: FlatMap[F]
  ): Iteratee[E2, F, A2] =
    Iteratee.iteratee(F.flatMap(step)(s => f(s).step))

  /**
   * Feed an enumerator to this iteratee.
   */
  final def feed(enumerator: Enumerator[E, F])(implicit F: FlatMap[F]): Iteratee[E, F, A] =
    Iteratee.iteratee(F.flatMap(step)(s => enumerator(s).step))

  /**
   * Feed an enumerator to this iteratee and run it to return an effectful value.
   */
  final def process(enumerator: Enumerator[E, F])(implicit F: Monad[F]): F[A] = feed(enumerator).run

  /**
   * 
   */
  final def through[O](enumeratee: Enumeratee[O, E, F])(implicit F: Monad[F]): Iteratee[O, F, A] =
    Iteratee.joinI(Iteratee.iteratee(F.flatMap(step)(s => enumeratee(s).step)))

  final def mapI[G[_]](f: NaturalTransformation[F, G])(implicit
    F: Functor[F]
  ): Iteratee[E, G, A] = {
    def transform: Step[E, F, A] => Step[E, G, A] = _.foldWith(
      new StepFolder[E, F, A, Step[E, G, A]] {
        def onCont(k: Input[E] => Iteratee[E, F, A]): Step[E, G, A] =
          Step.cont[E, G, A](in => loop(k(in)))
        def onDone(value: A, remainder: Input[E]): Step[E, G, A] =
          Step.done[E, G, A](value, remainder)
      }
    )

    def loop: Iteratee[E, F, A] => Iteratee[E, G, A] =
      i => Iteratee.iteratee(f(F.map(i.step)(transform)))

    loop(this)
  }

  final def up[G[_]](implicit G: Applicative[G], F: Comonad[F]): Iteratee[E, G, A] = mapI(
    new NaturalTransformation[F, G] {
      def apply[A](a: F[A]): G[A] = G.pure(F.extract(a))
    }
  )

  /**
   * Feeds input elements to this iteratee until it is done, feeds the produced value to the 
   * inner iteratee. Then this iteratee will start over, looping until the inner iteratee is done.
   */
  final def sequenceI(implicit m: Monad[F]): Enumeratee[E, A, F] = Enumeratee.sequenceI(this)

  final def zip[B](other: Iteratee[E, F, B])(implicit F: Monad[F]): Iteratee[E, F, (A, B)] = {
    type Pair[Z] = (Option[(Z, Input[E])], Iteratee[E, F, Z])

    def step[Z](i: Iteratee[E, F, Z]): Iteratee[E, F, Pair[Z]] = Iteratee.liftM(
      i.foldWith(
        new StepFolder[E, F, Z, Pair[Z]] {
          def onCont(k: Input[E] => Iteratee[E, F, Z]): Pair[Z] = (None, Iteratee.cont(k))
          def onDone(value: Z, remainder: Input[E]): Pair[Z] =
            (Some((value, remainder)), Iteratee.done(value, remainder))
        }
      )
    )

    def feedInput[Z](i: Iteratee[E, F, Z], in: Input[E]): Iteratee[E, F, Z] = i.advance(s =>
      s.foldWith(
        new MapContStepFolder[E, F, Z](s) {
          def onCont(k: Input[E] => Iteratee[E, F, Z]): Iteratee[E, F, Z] = k(in)
        }
      )
    )

    def loop(itA: Iteratee[E, F, A], itB: Iteratee[E, F, B])(in: Input[E]): Iteratee[E, F, (A, B)] =
      in.foldWith(
        new InputFolder[E, Iteratee[E, F, (A, B)]] {
          def onEmpty: Iteratee[E, F, (A, B)] = Iteratee.cont(loop(itA, itB))
          def onEl(e: E): Iteratee[E, F, (A, B)] = step(feedInput(itA, in)).flatMap {
            case (pairA, nextItA) =>
              step(feedInput(itB, in)).flatMap {
                case (pairB, nextItB) => (pairA, pairB) match {
                  case (Some((resA, remA)), Some((resB, remB))) =>
                    Iteratee.done((resA, resB), remA.shorter(remB))
                  case (Some((resA, _)), None) => nextItB.map((resA, _))
                  case (None, Some((resB, _))) => nextItA.map((_, resB))
                  case _ => Iteratee.cont(loop(nextItA, nextItB))
                }
              }
          }

          def onChunk(es: Vector[E]): Iteratee[E, F, (A, B)] = step(feedInput(itA, in)).flatMap {
            case (pairA, nextItA) =>
              step(feedInput(itB, in)).flatMap {
                case (pairB, nextItB) => (pairA, pairB) match {
                  case (Some((resA, remA)), Some((resB, remB))) =>
                    Iteratee.done((resA, resB), remA.shorter(remB))
                  case (Some((resA, _)), None) => nextItB.map((resA, _))
                  case (None, Some((resB, _))) => nextItA.map((_, resB))
                  case _ => Iteratee.cont(loop(nextItA, nextItB))
                }
              }
          }

          def onEnd: Iteratee[E, F, (A, B)] =
            itA.feed(Enumerator.enumEnd[E, F]).flatMap(a =>
              itB.feed(Enumerator.enumEnd[E, F]).map((a, _))
            )
      }
    )

    step(this).flatMap {
      case (pairA, nextItA) =>
        step(other).flatMap {
          case (pairB, nextItB) => (pairA, pairB) match {
            case (Some((resA, remA)), Some((resB, remB))) =>
              Iteratee.done((resA, resB), remA.shorter(remB))
            case (Some((resA, _)), None) => nextItB.map((resA, _))
            case (None, Some((resB, _))) => nextItA.map((_, resB))
            case _ => Iteratee.cont(loop(nextItA, nextItB))
          }
        }
    }
  }
}

sealed abstract class IterateeInstances0 {
  implicit final def IterateeMonad[E, F[_]](implicit
    F0: Monad[F]
  ): Monad[({ type L[x] = Iteratee[E, F, x] })#L] =
    new IterateeMonad[E, F] {
      implicit val F = F0
    }

  implicit final def PureIterateeMonad[E]: Monad[({ type L[x] = PureIteratee[E, x] })#L] =
    IterateeMonad[E, Id]
}

sealed abstract class IterateeInstances extends IterateeInstances0 {
  implicit def IterateeContravariant[
    F[_]: Monad,
    A
  ]: Contravariant[({ type L[x] = Iteratee[x, F, A] })#L] =
    new Contravariant[({ type L[x] = Iteratee[x, F, A] })#L] {
      def contramap[E, EE](r: Iteratee[E, F, A])(f: EE => E) = r.contramap(f)
    }
}

object Iteratee extends IterateeInstances {
  private[iteratee] final def diverge[A]: A = sys.error("Divergent iteratee")

  /**
   * Lift an effectful value into an iteratee.
   */
  final def liftM[E, F[_]: Monad, A](fa: F[A]): Iteratee[E, F, A] =
    iteratee(Monad[F].map(fa)(a => Step.done(a, Input.empty)))

  final def iteratee[E, F[_], A](s: F[Step[E, F, A]]): Iteratee[E, F, A] = new Iteratee[E, F, A](s)

  final def cont[E, F[_]: Applicative, A](c: Input[E] => Iteratee[E, F, A]): Iteratee[E, F, A] =
    Step.cont(c).pointI

  final def done[E, F[_]: Applicative, A](d: A, r: Input[E]): Iteratee[E, F, A] =
    Step.done(d, r).pointI

  final def identity[E, F[_]: Applicative]: Iteratee[E, F, Unit] = done[E, F, Unit]((), Input.empty)

  final def joinI[E, F[_]: Monad, I, B](it: Iteratee[E, F, Step[I, F, B]]): Iteratee[E, F, B] = {
    val M0 = Iteratee.IterateeMonad[E, F]

    def check: Step[I, F, B] => Iteratee[E, F, B] = _.foldWith(
      new StepFolder[I, F, B, Iteratee[E, F, B]] {
        def onCont(k: Input[I] => Iteratee[I, F, B]): Iteratee[E, F, B] = k(Input.end).advance(
          s => if (s.isDone) check(s) else diverge
        )
        def onDone(value: B, remainder: Input[I]): Iteratee[E, F, B] = M0.pure(value)
      }
    )

    it.flatMap(check)
  }

  /**
   * An iteratee that consumes all of the input into something that is MonoidK and Applicative.
   */
  final def consumeIn[E, F[_]: Monad, A[_]: MonoidK: Applicative]: Iteratee[E, F, A[E]] = {
    def step(s: Input[E]): Iteratee[E, F, A[E]] = s.foldWith(
      new InputFolder[E, Iteratee[E, F, A[E]]] {
        def onEmpty: Iteratee[E, F, A[E]] = cont(step)
        def onEl(e: E): Iteratee[E, F, A[E]] =
          cont(step).map(a => MonoidK[A].combine[E](Applicative[A].pure(e), a))
        def onChunk(es: Vector[E]): Iteratee[E, F, A[E]] =
          cont(step).map(a =>
            es.foldRight(a)((e, acc) => (MonoidK[A].combine[E](Applicative[A].pure(e), acc)))
          )
        def onEnd: Iteratee[E, F, A[E]] = done(MonoidK[A].empty[E], Input.end[E])
      }      
    )
    cont(step)
  }

  final def consume[E, F[_]: Monad]: Iteratee[E, F, Vector[E]] = {
    def step(s: Input[E]): Iteratee[E, F, Vector[E]] = s.foldWith(
      new InputFolder[E, Iteratee[E, F, Vector[E]]] {
        def onEmpty: Iteratee[E, F, Vector[E]] = cont(step)
        def onEl(e: E): Iteratee[E, F, Vector[E]] = cont(step).map(e +: _)
        def onChunk(es: Vector[E]): Iteratee[E, F, Vector[E]] = cont(step).map(es ++: _)
        def onEnd: Iteratee[E, F, Vector[E]] = done(Vector.empty[E], Input.end[E])
      }      
    )
    cont(step)
  }

  final def collectT[E, F[_], A[_]](implicit
    M: Monad[F],
    mae: Monoid[A[E]],
    pointed: Applicative[A]
  ): Iteratee[E, F, A[E]] = {
    import cats.syntax.semigroup._

    def step(s: Input[E]): Iteratee[E, F, A[E]] = s.foldWith(
      new InputFolder[E, Iteratee[E, F, A[E]]] {
        def onEmpty: Iteratee[E, F, A[E]] = cont(step)
        def onEl(e: E): Iteratee[E, F, A[E]] = cont(step).map(a => Applicative[A].pure(e) |+| a)
        def onChunk(es: Vector[E]): Iteratee[E, F, A[E]] =
          cont(step).map(a => es.foldRight(a)((e, acc) => Applicative[A].pure(e) |+| acc))
        def onEnd: Iteratee[E, F, A[E]] = done(Monoid[A[E]].empty, Input.end[E])
      }
    )

    cont(step)
  }

  /**
   * An iteratee that consumes the head of the input.
   */
  final def head[E, F[_] : Applicative]: Iteratee[E, F, Option[E]] = {
    def step(s: Input[E]): Iteratee[E, F, Option[E]] = s.normalize.foldWith(
      new InputFolder[E, Iteratee[E, F, Option[E]]] {
        def onEmpty: Iteratee[E, F, Option[E]] = cont(step)
        def onEl(e: E): Iteratee[E, F, Option[E]] = done(Some(e), Input.empty[E])
        def onChunk(es: Vector[E]): Iteratee[E, F, Option[E]] =
          done(Some(es.head), Input.chunk(es.tail))
        def onEnd: Iteratee[E, F, Option[E]] = done(None, Input.end[E])
      }
    )
    cont(step)
  }

  /**
   * An iteratee that returns the first element of the input.
   */
  final def peek[E, F[_]: Applicative]: Iteratee[E, F, Option[E]] = {
    def step(s: Input[E]): Iteratee[E, F, Option[E]] = s.normalize.foldWith(
      new InputFolder[E, Iteratee[E, F, Option[E]]] {
        def onEmpty: Iteratee[E, F, Option[E]] = cont(step)
        def onEl(e: E): Iteratee[E, F, Option[E]] = done(Some(e), s)
        def onChunk(es: Vector[E]): Iteratee[E, F, Option[E]] = done(Some(es.head), s)
        def onEnd: Iteratee[E, F, Option[E]] = done(None, Input.end[E])
      }
    )
    cont(step)
  }

  /**
   * Iteratee that collects all inputs in reverse with the given reducer.
   *
   * This iteratee is useful for `F[_]` with efficient cons, e.g. `List`.
   */
  final def reversed[A, F[_]: Applicative]: Iteratee[A, F, List[A]] =
    Iteratee.fold[A, F, List[A]](Nil)((acc, e) => e :: acc)

  /**
   * Iteratee that collects the first `n` inputs.
   */
  final def take[A, F[_]: Applicative](n: Int): Iteratee[A, F, Vector[A]] = {
    def loop(acc: Vector[A], n: Int)(in: Input[A]): Iteratee[A, F, Vector[A]] = in.foldWith(
      new InputFolder[A, Iteratee[A, F, Vector[A]]] {
        def onEmpty: Iteratee[A, F, Vector[A]] = cont(loop(acc, n))
        def onEl(e: A): Iteratee[A, F, Vector[A]] =
          if (n == 1) done(acc :+ e, Input.empty) else cont(loop(acc :+ e, n - 1))
        def onChunk(es: Vector[A]): Iteratee[A, F, Vector[A]] = {
          val diff = n - es.size

          if (diff > 0) cont(loop(acc ++ es, diff)) else
            if (diff == 0) done(acc ++ es, Input.empty) else {
              val (taken, left) = es.splitAt(n)

              done(acc ++ taken, Input.chunk(left))
            }
        }
        def onEnd: Iteratee[A, F, Vector[A]] = done(acc, in)
      }
    )

    if (n <= 0) done(Vector.empty, Input.empty) else cont(loop(Vector.empty, n))
  }

  /**
   * Iteratee that collects inputs until the input element fails a test.
   */
  final def takeWhile[A, F[_]: Applicative](p: A => Boolean): Iteratee[A, F, Vector[A]] = {
    def loop(acc: Vector[A])(in: Input[A]): Iteratee[A, F, Vector[A]] = in.foldWith(
      new InputFolder[A, Iteratee[A, F, Vector[A]]] {
        def onEmpty: Iteratee[A, F, Vector[A]] = cont(loop(acc))
        def onEl(e: A): Iteratee[A, F, Vector[A]] =
          if (p(e)) cont(loop(acc :+ e)) else done(acc, in)

        def onChunk(es: Vector[A]): Iteratee[A, F, Vector[A]] = {
          val (before, after) = es.span(p)

          if (after.isEmpty) cont(loop(acc ++ before)) else done(acc ++ before, Input.chunk(after))
        }
        def onEnd: Iteratee[A, F, Vector[A]] = done(acc, in)
      }
    )

    cont(loop(Vector.empty))
  }

  /**
   * An iteratee that skips the first `n` elements of the input.
   */
  final def drop[E, F[_]: Applicative](n: Int): Iteratee[E, F, Unit] = {
    def step(in: Input[E]): Iteratee[E, F, Unit] = in.foldWith(
      new InputFolder[E, Iteratee[E, F, Unit]] {
        def onEmpty: Iteratee[E, F, Unit] = cont(step)
        def onEl(e: E): Iteratee[E, F, Unit] = drop(n - 1)
        def onChunk(es: Vector[E]): Iteratee[E, F, Unit] = {
          val len = es.size

          if (len <= n) drop(n - len) else done((), Input.chunk(es.drop(n)))
        }
        def onEnd: Iteratee[E, F, Unit] = done((), in)
      }
    )

    if (n <= 0) done((), Input.empty) else cont(step)
  }

  /**
   * An iteratee that skips elements while the predicate evaluates to true.
   */
  final def dropWhile[E, F[_] : Applicative](p: E => Boolean): Iteratee[E, F, Unit] = {
    def step(s: Input[E]): Iteratee[E, F, Unit] = s.foldWith(
      new InputFolder[E, Iteratee[E, F, Unit]] {
        def onEmpty: Iteratee[E, F, Unit] = cont(step)
        def onEl(e: E): Iteratee[E, F, Unit] = if (p(e)) dropWhile(p) else done((), s)
        def onChunk(es: Vector[E]): Iteratee[E, F, Unit] = {
          val after = es.dropWhile(p)

          if (after.isEmpty) dropWhile(p) else done((), Input.chunk(after))
        }
        def onEnd: Iteratee[E, F, Unit] = done((), Input.end[E])
      }
    )

    cont(step)
  }

  final def fold[E, F[_]: Applicative, A](init: A)(f: (A, E) => A): Iteratee[E, F, A] = {
    def step(acc: A): Input[E] => Iteratee[E, F, A] = _.foldWith(
      new InputFolder[E, Iteratee[E, F, A]] {
        def onEmpty: Iteratee[E, F, A] = cont(step(acc))
        def onEl(e: E): Iteratee[E, F, A] = cont(step(f(acc, e)))
        def onChunk(es: Vector[E]): Iteratee[E, F, A] = cont(step(es.foldLeft(acc)(f)))
        def onEnd: Iteratee[E, F, A] = done(acc, Input.end[E])
      }
    )

    cont(step(init))
  }

  final def foldM[E, F[_], A](init: A)(f: (A, E) => F[A])(implicit
    F: Monad[F]
  ): Iteratee[E, F, A] = {
    def step(acc: A): Input[E] => Iteratee[E, F, A] = _.foldWith(
      new InputFolder[E, Iteratee[E, F, A]] {
        def onEmpty: Iteratee[E, F, A] = cont(step(acc))
        def onEl(e: E): Iteratee[E, F, A] = Iteratee.liftM(f(acc, e)).flatMap(a => cont(step(a)))
        def onChunk(es: Vector[E]): Iteratee[E, F, A] =
          Iteratee.liftM(
            es.foldLeft(F.pure(acc))((fa, e) => F.flatMap(fa)(a => f(a, e)))
          ).flatMap(a => cont(step(a)))
        def onEnd: Iteratee[E, F, A] = done(acc, Input.end[E])
      }
    )
    cont(step(init))
  }

  /**
   * An iteratee that counts and consumes the elements of the input
   */
  final def length[E, F[_]: Applicative]: Iteratee[E, F, Int] = fold(0)((a, _) => a + 1)

  /**
   * An iteratee that checks if the input is EOF.
   */
  final def isEnd[E, F[_]: Applicative]: Iteratee[E, F, Boolean] = cont(in => done(in.isEnd, in))

  final def sum[E: Monoid, F[_]: Monad]: Iteratee[E, F, E] =
    foldM[E, F, E](Monoid[E].empty)((a, e) => Applicative[F].pure(Monoid[E].combine(a, e)))
}

private trait IterateeMonad[E, F[_]] extends Monad[({ type L[x] = Iteratee[E, F, x] })#L] {
  implicit def F: Monad[F]

  final def pure[A](a: A): Iteratee[E, F, A] = Step.done(a, Input.empty).pointI
  override final def map[A, B](fa: Iteratee[E, F, A])(f: A => B): Iteratee[E, F, B] = fa.map(f)
  final def flatMap[A, B](fa: Iteratee[E, F, A])(f: A => Iteratee[E, F, B]): Iteratee[E, F, B] =
    fa.flatMap(f)
}
