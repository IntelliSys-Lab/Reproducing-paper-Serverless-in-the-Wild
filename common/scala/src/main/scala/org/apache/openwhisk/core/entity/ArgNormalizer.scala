/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.openwhisk.core.entity

import spray.json._
import scala.util.Try

protected[entity] trait ArgNormalizer[T] {

  protected[core] val serdes: RootJsonFormat[T]

  protected[entity] def factory(s: String): T = serdes.read(Try(s.parseJson).getOrElse(JsString(s)))

  /**
   * Creates a new T from string. The method checks that a string
   * argument is not null, not empty, and normalizes it by trimming
   * white space before creating new T.
   *
   * @param s is the string argument to supply to factory of T
   * @return T instance
   * @throws IllegalArgumentException if string is null or empty
   */
  @throws[IllegalArgumentException]
  protected[core] def apply(s: String): T = {
    require(s != null && s.trim.nonEmpty, "argument undefined")
    factory(s.trim)
  }
}

object ArgNormalizer {
  def trim(s: String) = Option(s) map { _.trim } getOrElse s
}
