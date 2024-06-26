/*
 * Copyright 2020 Spotify AB.
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

import cats.kernel.Eq

import scala.annotation.nowarn

sealed trait FallbackEqInstances {
  implicit def fallbackEq[A]: Eq[A] = new Eq[A] {
    def eqv(x: A, y: A): Boolean =
      (x, y) match {
        case (x: Array[_], y: Array[_]) => x.sameElements(y): @nowarn
        case _                          => Eq.fromUniversalEquals[A].eqv(x, y)
      }
  }
}

trait EqInstances extends FallbackEqInstances {
  // == does not compare arrays for value equality, but for reference equality.
  implicit def arrayEq[T](implicit eqT: Eq[T]): Eq[Array[T]] =
    new Eq[Array[T]] {
      def eqv(xs: Array[T], ys: Array[T]): Boolean =
        (xs.length == ys.length) &&
          xs.zip(ys).forall { case (x, y) => eqT.eqv(x, y) }
    }
}

object EqInstances extends EqInstances
