package spinoco.protocol.mime


import scodec._
import scodec.codecs._
import spinoco.protocol.common.codec._
import spinoco.protocol.mime.MediaType.{CustomMediaType, DefaultMediaType, MultipartMediaType}


sealed trait ContentType {
  def mediaType: MediaType
}

object ContentType {

  case class TextContent(mediaType: DefaultMediaType, charset: Option[MIMECharset]) extends ContentType
  case class BinaryContent(mediaType: DefaultMediaType) extends ContentType
  case class MultiPartContent(mediaType: MultipartMediaType) extends ContentType
  case class CustomContent(mediaType: CustomMediaType) extends ContentType


  val codec: Codec[ContentType] = {

    val semicolon = Codec(constantString1("; "), constantString1(";"))

    val charset: Codec[MIMECharset] = {
      ignoreWS ~> constantString1("charset=") ~> MIMECharset.codec <~ ignoreWS
    }
    val boundary: Codec[String] = {
      ignoreWS ~> constantString1("boundary=") ~> asciiToken <~ ignoreWS
    }

    val mediaTypeCodec =
      token(ascii, ';').exmap(MediaType.decodeString, MediaType.encodeString)

    (mediaTypeCodec ~ optional(
      lookahead2(constantString1(";"))
      , semicolon ~> choice(
        charset.xmap[Right[Nothing, MIMECharset]](Right(_), _.value).upcast[Either[String, MIMECharset]]
        , boundary.xmap[Left[String, Nothing]](Left(_), _.value).upcast[Either[String, MIMECharset]]
      )
    )).narrow[ContentType](
      {
        case (media: MultipartMediaType, Some(Left(boundary))) => Attempt.successful(MultiPartContent(media.copy(parameters = Map("boundary" -> boundary))))
        case (media: MultipartMediaType, _) => Attempt.failure(Err(s"Invalid media type. Parameter boundary is required for multipart media type : $media"))
        case (media: DefaultMediaType, maybeParem) => maybeParem match {
          case Some(Right(charset)) => if (media.isText) Attempt.successful(TextContent(media, Some(charset))) else Attempt.failure(Err(s"Only text media type supports charset : $media"))
          case _ => Attempt.successful(if (media.isText) TextContent(media, None) else BinaryContent(media))
        }
        case (media: CustomMediaType, _) => Attempt.successful(CustomContent(media))
      }
      , {
        case TextContent(media, charset) => (media, charset.map(Right(_)))
        case BinaryContent(media) => (media, None)
        case MultiPartContent(media) => (media, media.parameters.get("boundary").map(Left(_)))
        case CustomContent(media) => (media, None)
      }
    )

  }

}
