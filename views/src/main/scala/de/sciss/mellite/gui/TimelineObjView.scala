package de.sciss.mellite
package gui

import de.sciss.lucre.event.Sys
import de.sciss.lucre.expr.Expr
import de.sciss.lucre.stm
import de.sciss.lucre.stm.IdentifierMap
import de.sciss.span.{Span, SpanLike}
import de.sciss.synth.proc.{Timeline, FadeSpec, Obj, Elem}

import scala.language.higherKinds
import scala.language.implicitConversions

object TimelineObjView {
  type SelectionModel[S <: Sys[S]] = gui.SelectionModel[S, TimelineObjView[S]]

  final val Unnamed = "<unnamed>"

  implicit def span[S <: Sys[S]](view: TimelineObjView[S]): (Long, Long) = {
    view.spanValue match {
      case Span(start, stop)  => (start, stop)
      case Span.From(start)   => (start, Long.MaxValue)
      case Span.Until(stop)   => (Long.MinValue, stop)
      case Span.All           => (Long.MinValue, Long.MaxValue)
      case Span.Void          => (Long.MinValue, Long.MinValue)
    }
  }

  type Map[S <: Sys[S]] = IdentifierMap[S#ID, S#Tx, TimelineObjView[S]]

  trait Factory {
    //    def prefix: String
    //    def icon  : Icon
    def typeID: Int

    type E[~ <: Sys[~]] <: Elem[~]

    def apply[S <: Sys[S]](obj: Obj.T[S, E])(implicit tx: S#Tx): TimelineObjView[S]
  }

  def addFactory(f: Factory): Unit = ??? // Impl.addFactory(f)

  def factories: Iterable[Factory] = ??? // Impl.factories

  def apply[S <: Sys[S]](timed: Timeline.Timed[S])(implicit tx: S#Tx): TimelineObjView[S] = ??? // Impl(obj)

  // ---- specialization ----

  final val attrTrackIndex  = "track-index"
  final val attrTrackHeight = "track-height"

  trait HasMute {
    var muted: Boolean
  }

  trait HasGain {
    var gain: Double
  }

  trait HasFade {
    var fadeIn : FadeSpec
    var fadeOut: FadeSpec
  }
}
trait TimelineObjView[S <: Sys[S]] {
  /** The proc's name or a place holder name if no name is set. */
  //def name: String

  var nameOption: Option[String]

  /** Convenience method that returns an "unnamed" string if no name is set. */
  def name: String = nameOption.getOrElse(TimelineObjView.Unnamed)

  def span: stm.Source[S#Tx, Expr[S, SpanLike]]
  def obj : stm.Source[S#Tx, Obj [S]]

  var spanValue: SpanLike

  var trackIndex : Int
  var trackHeight: Int
}