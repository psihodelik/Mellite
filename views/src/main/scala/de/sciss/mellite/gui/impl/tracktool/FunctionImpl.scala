/*
 *  FunctionImpl.scala
 *  (Mellite)
 *
 *  Copyright (c) 2012-2015 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU General Public License v3+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.mellite
package gui
package impl
package tracktool

import java.awt.Cursor
import java.awt.event.MouseEvent
import javax.swing.undo.UndoableEdit

import de.sciss.lucre.bitemp.{Span => SpanEx}
import de.sciss.lucre.expr.{Expr, Int => IntEx}
import de.sciss.lucre.stm
import de.sciss.lucre.synth.Sys
import de.sciss.mellite.gui.edit.EditTimelineInsertObj
import de.sciss.span.{Span, SpanLike}
import de.sciss.synth.proc.{IntElem, Obj, Proc}

final class FunctionImpl[S <: Sys[S]](protected val canvas: TimelineProcCanvas[S])
  extends RegionLike[S, TrackTool.Function] with Dragging[S, TrackTool.Function] {

  import TrackTool.{Cursor => _, _}

  def defaultCursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
  val name          = "Function"
  val icon          = ToolsImpl.getIcon("function")

  protected type Initial = Unit

  protected def handlePress(e: MouseEvent, hitTrack: Int, pos: Long, regionOpt: Option[TimelineObjView[S]]): Unit = {
    handleMouseSelection(e, regionOpt)
    regionOpt match {
      case Some(region) =>
        if (e.getClickCount == 2) {
          println("Edit TODO")
        }

      case _  => new Drag(e, hitTrack, pos, ())
    }
  }

  protected def dragToParam(d: Drag): Function = {
    val dStart  = math.min(d.firstPos, d.currentPos)
    val dStop   = math.max(dStart + BasicRegion.MinDur, math.max(d.firstPos, d.currentPos))
    val dTrkIdx = math.min(d.firstTrack, d.currentTrack)
    val dTrkH   = math.max(d.firstTrack, d.currentTrack) - dTrkIdx + 1

    Function(trackIndex = dTrkIdx, trackHeight = dTrkH, span = Span(dStart, dStop))
  }

  def commit(drag: Function)(implicit tx: S#Tx, cursor: stm.Cursor[S]): Option[UndoableEdit] =
    canvas.timeline.modifiableOption.map { g =>
      val span  = SpanEx.newVar[S](SpanEx.newConst(drag.span)) : Expr[S, SpanLike]
      val p     = Proc[S]
      val obj   = Obj(Proc.Elem(p))
      obj.attr.put(TimelineObjView.attrTrackIndex , Obj(IntElem(IntEx.newVar(IntEx.newConst(drag.trackIndex )))))
      obj.attr.put(TimelineObjView.attrTrackHeight, Obj(IntElem(IntEx.newVar(IntEx.newConst(drag.trackHeight)))))
      log(s"Add function region $p, span = ${drag.span}, trackIndex = ${drag.trackIndex}")
      // import SpanLikeEx.serializer
      EditTimelineInsertObj(name, g, span, obj)
      // g.add(span, obj)

      // canvas.selectionModel.clear()
      // canvas.selectionModel += ?
    }
}
