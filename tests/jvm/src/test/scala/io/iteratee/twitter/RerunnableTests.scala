package io.iteratee.twitter

import algebra.Eq
import com.twitter.conversions.time._
import io.catbird.util.Rerunnable
import io.iteratee.tests.{ EnumerateeSuite, EnumeratorSuite, IterateeErrorSuite, ModuleSuite, eqThrowable }

trait RerunnableSuite { this: ModuleSuite[Rerunnable] =>
  def monadName: String = "Rerunnable"

  implicit def eqF[A: Eq]: Eq[Rerunnable[A]] = Rerunnable.rerunnableEqWithFailure[A](2.seconds)
}

class RerunnableEnumerateeTests extends EnumerateeSuite[Rerunnable] with RerunnableSuite

class RerunnableEnumeratorTests extends EnumeratorSuite[Rerunnable] with RerunnableSuite

class RerunnableIterateeTests extends IterateeErrorSuite[Rerunnable, Throwable] with RerunnableSuite
