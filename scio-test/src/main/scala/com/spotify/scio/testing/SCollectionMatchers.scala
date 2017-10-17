/*
 * Copyright 2016 Spotify AB.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.spotify.scio.testing

import java.lang.{Iterable => JIterable}
import java.util.{Map => JMap}

import com.spotify.scio.util.ClosureCleaner
import com.spotify.scio.values.SCollection
import org.apache.beam.sdk.testing.PAssert
import org.apache.beam.sdk.testing.PAssert.{IterableAssert, SingletonAssert}
import org.apache.beam.sdk.transforms.SerializableFunction
import org.apache.beam.sdk.transforms.windowing.BoundedWindow
import org.apache.beam.sdk.util.CoderUtils
import org.scalatest.matchers.{MatchResult, Matcher}

import scala.collection.JavaConverters._
import scala.reflect.ClassTag

/**
 * Trait with ScalaTest [[org.scalatest.matchers.Matcher Matcher]]s for
 * [[com.spotify.scio.values.SCollection SCollection]]s.
 */
trait SCollectionMatchers {

  import scala.language.higherKinds

  sealed trait MatcherBuilder[T, B, A[_]] { self: Matcher[T] =>
    type AssertFn = T => A[B]

    def assertFn: AssertFn

    def matcher(fn: AssertFn): Matcher[T]

    def matcher: Matcher[T] = matcher(assertFn)
  }

  sealed trait IterableMatcher[T, B] extends MatcherBuilder[T, B, IterableAssert]
    with Matcher[T] {
    override def apply(left: T): MatchResult = matcher(left)
  }

  sealed trait SingleMatcher[T, B] extends MatcherBuilder[T, B, SingletonAssert]
    with Matcher[T] {
    override def apply(left: T): MatchResult = matcher(left)
  }

  /*
  Wrapper for PAssert statements. PAssert does not perform assertions or throw exceptions until
  sc.close() is called. So MatchResult should always match true for "a should $Matcher" cases and
  false for "a shouldNot $Matcher" cases. We also need to run different assertions for positive
  (shouldFn) and negative (shouldNotFn) cases.
   */
  private def m(shouldFn: () => Any, shouldNotFn: () => Any): MatchResult = {
    val isShouldNot = Thread.currentThread()
      .getStackTrace
      .exists(e => e.getClassName.startsWith("org.scalatest.") && e.getMethodName == "shouldNot")
    val r = if (isShouldNot) {
      shouldNotFn()
      false
    } else {
      shouldFn()
      true
    }
    MatchResult(r, "", "")
  }

  private def makeFn[T](f: JIterable[T] => Unit): SerializableFunction[JIterable[T], Void] =
    new SerializableFunction[JIterable[T], Void] {
      override def apply(input: JIterable[T]) = {
        f(input)
        null
      }
    }

  // Due to  https://github.com/GoogleCloudPlatform/DataflowJavaSDK/issues/434
  // SerDe cycle on each element to keep consistent with values on the expected side
  private def serDeCycle[T: ClassTag](scollection: SCollection[T]): SCollection[T] = {
    val coder = scollection.internal.getCoder
    scollection
      .map(e => CoderUtils.decodeFromByteArray(coder, CoderUtils.encodeToByteArray(coder, e)))
  }

  /** SCollection assertion only applied to the specified window,
   * running the checker only on the on-time pane for each key. */
  def inOnTimePane[T: ClassTag, B: ClassTag, A[_]](window: BoundedWindow)
                                                  (matcher: MatcherBuilder[T, B, A])
  : Matcher[T] =
    if (matcher.isInstanceOf[IterableMatcher[T, B]]) {
      val mt = matcher.asInstanceOf[IterableMatcher[T, B]]
      mt.matcher(mt.assertFn.andThen(_.inOnTimePane(window)))
    } else {
      val mt = matcher.asInstanceOf[SingleMatcher[T, B]]
      mt.matcher(mt.assertFn.andThen(_.inOnTimePane(window)))
    }

  /** SCollection assertion only applied to the specified window. */
  def inWindow[T: ClassTag, B: ClassTag, A[_]](window: BoundedWindow)
                                              (matcher: IterableMatcher[T, B])
  : Matcher[T] =
    matcher.matcher(matcher.assertFn.andThen(_.inWindow(window)))

  /** SCollection assertion only applied to the specified window across
   * all panes that were not produced by the arrival of late data */
  def inCombinedNonLatePanes[T: ClassTag, B: ClassTag, A[_]](window: BoundedWindow)
                                                            (matcher: IterableMatcher[T, B])
  : Matcher[T] =
    matcher.matcher(matcher.assertFn.andThen(_.inCombinedNonLatePanes(window)))

  /** SCollection assertion only applied to the specified window,
   * running the checker only on the final pane for each key. */
  def inFinalPane[T: ClassTag, B: ClassTag, A[_]](window: BoundedWindow)
                                                 (matcher: MatcherBuilder[T, B, A])
  : Matcher[T] =
    if (matcher.isInstanceOf[IterableMatcher[T, B]]) {
      val mt = matcher.asInstanceOf[IterableMatcher[T, B]]
      mt.matcher(mt.assertFn.andThen(_.inFinalPane(window)))
    } else {
      val mt = matcher.asInstanceOf[SingleMatcher[T, B]]
      mt.matcher(mt.assertFn.andThen(_.inFinalPane(window)))
    }

  /** SCollection assertion only applied to early timing global window. */
  def inEarlyGlobalWindowPanes[T: ClassTag, B: ClassTag, A[_]](matcher: IterableMatcher[T, B])
  : Matcher[T] =
    matcher.matcher(matcher.assertFn.andThen(_.inEarlyGlobalWindowPanes))

  /** Assert that the SCollection in question contains the provided elements. */
  def containInAnyOrder[T: ClassTag](value: Iterable[T])
  : IterableMatcher[SCollection[T], T] = new IterableMatcher[SCollection[T], T] {
    override def assertFn: AssertFn =
      l => PAssert.that(serDeCycle(l).internal)

    override def matcher(assertFn: AssertFn): Matcher[SCollection[T]] =
      new Matcher[SCollection[T]] {
        override def apply(left: SCollection[T]): MatchResult = {
          val v = value // defeat closure
          val f = makeFn[T] { in =>
            import org.hamcrest.Matchers
            import org.junit.Assert
            Assert.assertThat(in, Matchers.not(Matchers.containsInAnyOrder(v.toSeq: _*)))
          }
          m(
            () => assertFn(left).containsInAnyOrder(value.asJava),
            () => assertFn(left).satisfies(f))
        }
      }
  }

  /** Assert that the SCollection in question contains a single provided element. */
  def containSingleValue[T: ClassTag](value: T)
  : SingleMatcher[SCollection[T], T] = new SingleMatcher[SCollection[T], T] {
    override def assertFn: AssertFn = l => PAssert.thatSingleton(serDeCycle(l).internal)

    override def matcher(assertFn: AssertFn): Matcher[SCollection[T]] =
      new Matcher[SCollection[T]] {
        override def apply(left: SCollection[T]): MatchResult = m(
          () => assertFn(left).isEqualTo(value),
          () => assertFn(left).notEqualTo(value))
      }
  }

  /** Assert that the SCollection in question is empty. */
  def beEmpty[T: ClassTag]: IterableMatcher[SCollection[_], Any] =
    new IterableMatcher[SCollection[_], Any] {
      override def assertFn: AssertFn =
        left => PAssert.that(left.asInstanceOf[SCollection[Any]].internal)

      override def matcher(assertFn: AssertFn): Matcher[SCollection[_]] =
        new Matcher[SCollection[_]] {
          override def apply(left: SCollection[_]): MatchResult = m(
            () => assertFn(left).empty(),
            () => assertFn(left).satisfies(makeFn(in =>
              assert(in.iterator().hasNext, "SCollection is empty"))))
        }
    }

  /** Assert that the SCollection in question has provided size. */
  def haveSize(size: Int): IterableMatcher[SCollection[_], Any] =
    new IterableMatcher[SCollection[_], Any] {
      override def assertFn: AssertFn =
        left => PAssert.that(left.asInstanceOf[SCollection[Any]].internal)

      override def matcher(assertFn: AssertFn): Matcher[SCollection[_]] =
        new Matcher[SCollection[_]] {
          override def apply(left: SCollection[_]): MatchResult = {
            val s = size // defeat closure
            val f = makeFn[Any] { in =>
              val inSize = in.asScala.size
              assert(inSize == s, s"SCollection expected size: $s, actual: $inSize")
            }
            val g = makeFn[Any] { in =>
              val inSize = in.asScala.size
              assert(inSize != s, s"SCollection expected size: not $s, actual: $inSize")
            }
            m(
              () => assertFn(left).satisfies(f),
              () => assertFn(left).satisfies(g))
          }
        }
    }

  /** Assert that the SCollection in question is equivalent to the provided map. */
  def equalMapOf[K: ClassTag, V: ClassTag](value: Map[K, V])
  : SingleMatcher[SCollection[(K, V)], JMap[K, V]] =
    new SingleMatcher[SCollection[(K, V)], JMap[K, V]] {
      override def assertFn: SCollection[(K, V)] => SingletonAssert[JMap[K, V]] =
        l => PAssert.thatMap(serDeCycle(l).toKV.internal)

      override def matcher(assertFn: AssertFn): Matcher[SCollection[(K, V)]] =
        new Matcher[SCollection[(K, V)]] {
          override def apply(left: SCollection[(K, V)]): MatchResult = m(
            () => assertFn(left).isEqualTo(value.asJava),
            () => assertFn(left).notEqualTo(value.asJava))
        }
    }

  // TODO: investigate why multi-map doesn't work

  /** Assert that the SCollection in question satisfies the provided function. */
  def satisfy[T: ClassTag](predicate: Iterable[T] => Boolean)
  : IterableMatcher[SCollection[T], T] = new IterableMatcher[SCollection[T], T] {
    override def assertFn: AssertFn = left => PAssert.that(left.internal)

    override def matcher(assertFn: AssertFn): Matcher[SCollection[T]] =
      new Matcher[SCollection[T]] {
        override def apply(left: SCollection[T]): MatchResult = {
          val p = ClosureCleaner(predicate)
          val f = makeFn[T](in => assert(p(in.asScala)))
          val g = makeFn[T](in => assert(!p(in.asScala)))
          m(
            () => assertFn(serDeCycle(left)).satisfies(f),
            () => assertFn(serDeCycle(left)).satisfies(g))
        }
      }
  }

  /** Assert that all elements of the SCollection in question satisfy the provided function. */
  def forAll[T: ClassTag](predicate: T => Boolean): IterableMatcher[SCollection[T], T] =
    satisfy(_.forall(predicate))

  /** Assert that some elements of the SCollection in question satisfy the provided function. */
  def exist[T: ClassTag](predicate: T => Boolean): IterableMatcher[SCollection[T], T] =
    satisfy(_.exists(predicate))


}
