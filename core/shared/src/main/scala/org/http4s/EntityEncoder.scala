/*
 * Copyright 2013 http4s.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.http4s

import cats.Contravariant
import cats.Show
import cats.data.NonEmptyList
import cats.effect.Sync
import cats.syntax.all._
import fs2.Chunk
import fs2.Stream
import fs2.io.file.Files
import fs2.io.readInputStream
import org.http4s.Charset.`UTF-8`
import org.http4s.headers._
import org.http4s.multipart.Multipart
import org.http4s.multipart.MultipartEncoder
import scodec.bits.ByteVector

import java.io._
import java.nio.CharBuffer
import scala.annotation.implicitNotFound

@implicitNotFound(
  "Cannot convert from ${A} to an Entity, because no EntityEncoder[${F}, ${A}] instance could be found."
)
trait EntityEncoder[+F[_], A] { self =>

  /** Convert the type `A` to an [[Entity]] in the effect type `F` */
  def toEntity(a: A): Entity[F]

  /** Headers that may be added to a [[Message]]
    *
    * Examples of such headers would be Content-Type.
    * __NOTE:__ The Content-Length header will be generated from the resulting Entity and thus should not be added.
    */
  def headers: Headers

  /** Make a new [[EntityEncoder]] using this type as a foundation */
  def contramap[B](f: B => A): EntityEncoder[F, B] =
    new EntityEncoder[F, B] {
      override def toEntity(a: B): Entity[F] = self.toEntity(f(a))
      override def headers: Headers = self.headers
    }

  /** Get the [[org.http4s.headers.`Content-Type`]] of the body encoded by this [[EntityEncoder]],
    * if defined the headers
    */
  def contentType: Option[`Content-Type`] = headers.get[`Content-Type`]

  /** Get the [[Charset]] of the body encoded by this [[EntityEncoder]], if defined the headers */
  def charset: Option[Charset] = headers.get[`Content-Type`].flatMap(_.charset)

  /** Generate a new EntityEncoder that will contain the `Content-Type` header */
  def withContentType(tpe: `Content-Type`): EntityEncoder[F, A] =
    new EntityEncoder[F, A] {
      override def toEntity(a: A): Entity[F] = self.toEntity(a)
      override val headers: Headers = self.headers.put(tpe)
    }
}

object EntityEncoder {
  type Pure[A] = EntityEncoder[fs2.Pure, A]
  object Pure {
    def apply[A](implicit ev: EntityEncoder.Pure[A]): EntityEncoder.Pure[A] = ev
  }

  private val DefaultChunkSize = 4096

  /** summon an implicit [[EntityEncoder]] */
  def apply[F[_], A](implicit ev: EntityEncoder[F, A]): EntityEncoder[F, A] = ev

  /** Create a new [[EntityEncoder]] */
  def encodeBy[F[_], A](hs: Headers)(f: A => Entity[F]): EntityEncoder[F, A] =
    new EntityEncoder[F, A] {
      override def toEntity(a: A): Entity[F] = f(a)
      override def headers: Headers = hs
    }

  /** Create a new [[EntityEncoder]] */
  def encodeBy[F[_], A](hs: Header.ToRaw*)(f: A => Entity[F]): EntityEncoder[F, A] = {
    val hdrs = if (hs.nonEmpty) Headers(hs: _*) else Headers.empty
    encodeBy(hdrs)(f)
  }

  /** Create a new [[EntityEncoder]]
    *
    * This constructor is a helper for types that can be serialized synchronously, for example a String.
    */
  def simple[A](hs: Header.ToRaw*)(toByteVector: A => ByteVector): EntityEncoder.Pure[A] =
    encodeBy(hs: _*)(a => Entity.strict(toByteVector(a)))

  /** Encodes a value from its Show instance.  Too broad to be implicit, too useful to not exist. */
  def showEncoder[A](implicit charset: Charset = `UTF-8`, show: Show[A]): EntityEncoder.Pure[A] =
    stringEncoder.contramap(show.show)

  def emptyEncoder[A]: EntityEncoder.Pure[A] =
    new EntityEncoder[fs2.Pure, A] {
      def toEntity(a: A): Entity[fs2.Pure] = Entity.empty
      def headers: Headers = Headers.empty
    }

  /** A stream encoder is intended for streaming, and does not calculate its
    * bodies in advance.  As such, it does not calculate the Content-Length in
    * advance.  This is for use with chunked transfer encoding.
    */
  implicit def streamEncoder[F[_], A](implicit
      W: EntityEncoder[F, A]
  ): EntityEncoder[F, Stream[F, A]] =
    new EntityEncoder[F, Stream[F, A]] {
      override def toEntity(a: Stream[F, A]): Entity[F] =
        Entity.stream(a.flatMap(W.toEntity(_).body))

      override def headers: Headers =
        W.headers.get[`Transfer-Encoding`] match {
          case Some(transferCoding) if transferCoding.hasChunked =>
            W.headers
          case _ =>
            W.headers.add(`Transfer-Encoding`(TransferCoding.chunked.pure[NonEmptyList]))
        }
    }

  implicit val unitEncoder: EntityEncoder.Pure[Unit] =
    emptyEncoder[Unit]

  implicit def stringEncoder(implicit charset: Charset = `UTF-8`): EntityEncoder.Pure[String] = {
    val hdr = `Content-Type`(MediaType.text.plain).withCharset(charset)
    simple(hdr)(s => ByteVector.view(s.getBytes(charset.nioCharset)))
  }

  implicit def charArrayEncoder(implicit
      charset: Charset = `UTF-8`
  ): EntityEncoder.Pure[Array[Char]] =
    stringEncoder.contramap(new String(_))

  implicit val byteVectorEncoder: EntityEncoder.Pure[ByteVector] =
    simple(`Content-Type`(MediaType.application.`octet-stream`))(identity)

  implicit val chunkEncoder: EntityEncoder.Pure[Chunk[Byte]] =
    byteVectorEncoder.contramap(_.toByteVector)

  implicit val byteArrayEncoder: EntityEncoder.Pure[Array[Byte]] =
    byteVectorEncoder.contramap(ByteVector.view)

  /** Encodes an entity body.  Chunking of the stream is preserved.  A
    * `Transfer-Encoding: chunked` header is set, as we cannot know
    * the content length without running the stream.
    */
  implicit def entityBodyEncoder[F[_]]: EntityEncoder[F, Stream[F, Byte]] =
    encodeBy(`Transfer-Encoding`(TransferCoding.chunked.pure[NonEmptyList])) { body =>
      Entity.stream(body, None)
    }

  // TODO if Header moves to Entity, can add a Content-Disposition with the filename
  implicit def pathEncoder[F[_]: Files]: EntityEncoder[F, fs2.io.file.Path] =
    encodeBy[F, fs2.io.file.Path](`Transfer-Encoding`(TransferCoding.chunked)) { p =>
      Entity.stream(Files[F].readAll(p))
    }

  implicit def inputStreamEncoder[F[_]: Sync, IS <: InputStream]: EntityEncoder[F, F[IS]] =
    entityBodyEncoder[F].contramap { (in: F[IS]) =>
      readInputStream[F](in.widen[InputStream], DefaultChunkSize)
    }

  // TODO parameterize chunk size
  implicit def readerEncoder[F[_], R <: Reader](implicit
      F: Sync[F],
      charset: Charset = `UTF-8`,
  ): EntityEncoder[F, F[R]] =
    entityBodyEncoder[F].contramap { (fr: F[R]) =>
      // Shared buffer
      val charBuffer = CharBuffer.allocate(DefaultChunkSize)
      def readToBytes(r: Reader): F[Option[Chunk[Byte]]] =
        for {
          // Read into the buffer
          readChars <- F.blocking(r.read(charBuffer))
        } yield {
          // Flip to read
          charBuffer.flip()

          if (readChars < 0) None
          else if (readChars == 0) Some(Chunk.empty)
          else {
            // Encode to bytes according to the charset
            val bb = charset.nioCharset.encode(charBuffer)
            // Read into a Chunk
            val b = new Array[Byte](bb.remaining())
            bb.get(b)
            Some(Chunk.array(b))
          }
        }

      def useReader(r: Reader) =
        Stream
          .eval(readToBytes(r))
          .repeat
          .unNoneTerminate
          .flatMap(Stream.chunk[F, Byte])

      // The reader is closed at the end like InputStream
      Stream.bracket(fr)(r => F.delay(r.close())).flatMap(useReader)
    }

  implicit def multipartEncoder[F[_]]: EntityEncoder[F, Multipart[F]] =
    new MultipartEncoder[F]

  implicit def entityEncoderContravariant[F[_]]: Contravariant[EntityEncoder[F, *]] =
    new Contravariant[EntityEncoder[F, *]] {
      override def contramap[A, B](r: EntityEncoder[F, A])(f: (B) => A): EntityEncoder[F, B] =
        r.contramap(f)
    }

  implicit def serverSentEventEncoder[F[_]]: EntityEncoder[F, EventStream[F]] =
    entityBodyEncoder[F]
      .contramap[EventStream[F]](_.through(ServerSentEvent.encoder))
      .withContentType(`Content-Type`(MediaType.`text/event-stream`))
}
