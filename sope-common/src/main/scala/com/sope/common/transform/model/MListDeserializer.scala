package com.sope.common.transform.model

import com.fasterxml.jackson.core.{JsonParser, JsonToken}
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.sope.common.annotations.SqlExpr
import com.sope.common.transform.model.action.TransformActionRoot
import com.sope.common.transform.model.io.input.SourceTypeRoot
import com.sope.common.transform.model.io.output.TargetTypeRoot
import com.sope.common.utils.Logging

import scala.collection.mutable
import scala.reflect.ClassTag
import scala.reflect.runtime.universe._

/**
 * [[MList]] Deserializer. This does a best effort to record failures during deserialization of the provided type.
 * The Goal is to not fail the deserialization process but to record the failures, which may then be used to report
 * all of them at once.
 *
 * @author mbadgujar
 */
class MListDeserializer[T: ClassTag](clz: Class[T]) extends StdDeserializer[MList[T]](classOf[MList[T]]) with Logging {

  override def deserialize(p: JsonParser, ctxt: DeserializationContext): MList[T] = {
    val mirror = runtimeMirror(this.getClass.getClassLoader)
    val data = mutable.MutableList[T]()
    val failures = mutable.MutableList[Failed]()
    val sqlExprs = mutable.MutableList[SqlExpression]()

    /*
       In case the token does not start array, return failure straight away
      */
    if (p.getCurrentToken != JsonToken.START_ARRAY) {
      val location = p.getCurrentLocation
      log.error(s"Invalid list definition for ${p.getCurrentName} tag")
      failures += Failed("Invalid yaml list definition", location.getLineNr, location.getColumnNr)
      return MList(data, failures)
    }

    while (p.nextToken() != JsonToken.END_ARRAY) {
      if (p.getCurrentToken == JsonToken.START_OBJECT && Option(p.getCurrentName).isEmpty) {
        val location = p.getCurrentLocation
        try {
          val validElem = p.readValueAs[T](clz)
          // Check if the element has any SQL expression/ SQL to be validated
          val clazz = mirror.staticClass(validElem.getClass.getCanonicalName)
          val objMirror = mirror.reflect(validElem)
          clazz.selfType.members.collect {
            case m: MethodSymbol if m.isCaseAccessor && m.annotations.exists(_.tree.tpe =:= typeOf[SqlExpr]) =>
              val expr = objMirror.reflectField(m).get
              if (m.name.toString.trim == "sql") (expr, true) else (expr, false)
          }.foreach {
            case (None, _) =>
            case (expr, isSql) => sqlExprs += SqlExpression(expr, isSql, location.getLineNr, location.getColumnNr)
          }
          log.trace(s"Successfully Parsed element of type $clz :- $validElem")
          data += validElem
        }
        catch {
          case e: Exception =>
            //e.printStackTrace()
            log.error(s"Parsing failed with message ${e.getMessage} at ${location.getLineNr}:${location.getColumnNr}")
            failures += Failed(e.getMessage, location.getLineNr, location.getColumnNr)
        }
      } else {
        // Cases where the next token might be an internal object/array as result of failure on the root object.
        // These are skipped and token is moved to next object at root.
        if ((p.getCurrentToken == JsonToken.START_OBJECT || p.getCurrentToken == JsonToken.START_ARRAY)
          && Option(p.getCurrentName).isDefined) {
          log.debug("Skipping Current Token: " + p.getCurrentToken + " with Name: " + p.getCurrentName)
          p.skipChildren()
        }
      }
    }
    MList(data, failures, sqlExprs)
  }

  override def getNullValue(ctxt: DeserializationContext): MList[T] = MList[T](Nil)
}

object MListDeserializer {

  class TransformationDeserializer[D] extends MListDeserializer(classOf[Transformation[D]])

  class ActionDeserializer[D] extends MListDeserializer(classOf[TransformActionRoot[D]])

  class InputDeserializer[CTX, D] extends MListDeserializer(classOf[SourceTypeRoot[CTX, D]])

  class TargetDeserializer[D] extends MListDeserializer(classOf[TargetTypeRoot[D]])

}