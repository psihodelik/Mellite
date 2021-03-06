/*
 *  Edits.scala
 *  (Mellite)
 *
 *  Copyright (c) 2012-2016 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU General Public License v3+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.mellite
package gui
package edit

import javax.swing.undo.UndoableEdit

import de.sciss.desktop.edit.CompoundEdit
import de.sciss.lucre.expr.{IntObj, LongObj, SpanLikeObj, StringObj}
import de.sciss.lucre.{expr, stm}
import de.sciss.lucre.stm.{Obj, Sys}
import de.sciss.lucre.swing.edit.EditVar
import de.sciss.mellite.ProcActions.{Move, Resize}
import de.sciss.span.{Span, SpanLike}
import de.sciss.synth.{SynthGraph, proc}
import de.sciss.synth.proc.{AudioCue, Code, Folder, ObjKeys, Output, Proc, SynthGraphObj}

import scala.collection.breakOut
import scala.collection.immutable.{Seq => ISeq}
import scala.util.control.NonFatal

object Edits {
  def setBus[S <: Sys[S]](objects: Iterable[Obj[S]], intExpr: IntObj[S])
                         (implicit tx: S#Tx, cursor: stm.Cursor[S]): Option[UndoableEdit] = {
    val name  = "Set Bus"
    implicit val intTpe = IntObj
    val edits: List[UndoableEdit] = objects.map { obj =>
      EditAttrMap.expr[S, Int, IntObj](name, obj, ObjKeys.attrBus, Some(intExpr))
//      { ex =>
//        IntObj(IntObj.newVar(ex))
//      }
    } (breakOut)
    CompoundEdit(edits, name)
  }

  def setSynthGraph[S <: Sys[S]](procs: Iterable[Proc[S]], codeElem: Code.Obj[S])
                                (implicit tx: S#Tx, cursor: stm.Cursor[S],
                                 compiler: Code.Compiler): Option[UndoableEdit] = {
    val code = codeElem.value
    code match {
      case csg: Code.SynthGraph =>
        val sg = try {
          csg.execute {}  // XXX TODO: compilation blocks, not good!
        } catch {
          case NonFatal(e) =>
            e.printStackTrace()
            return None
        }

        var scanInKeys  = Set.empty[String]
        var scanOutKeys = Set.empty[String]

        sg.sources.foreach {
          case proc.graph.ScanIn   (key)    => scanInKeys  += key
          case proc.graph.ScanOut  (key, _) => scanOutKeys += key
          case proc.graph.ScanInFix(key, _) => scanInKeys  += key
          case _ =>
        }

        // sg.sources.foreach(println)
        if (scanInKeys .nonEmpty) log(s"SynthDef has the following scan in  keys: ${scanInKeys .mkString(", ")}")
        if (scanOutKeys.nonEmpty) log(s"SynthDef has the following scan out keys: ${scanOutKeys.mkString(", ")}")

        val editName    = "Set Synth Graph"
        val attrNameOpt = codeElem.attr.get(ObjKeys.attrName)
        val edits       = List.newBuilder[UndoableEdit]

        procs.foreach { p =>
          val graphEx = SynthGraphObj.newConst[S](sg)  // XXX TODO: ideally would link to code updates
          implicit val sgTpe = SynthGraphObj
          val edit1   = EditVar.Expr[S, SynthGraph, SynthGraphObj](editName, p.graph, graphEx)
          edits += edit1
          if (attrNameOpt.nonEmpty) {
            val edit2 = EditAttrMap("Set Object Name", p, ObjKeys.attrName, attrNameOpt)
            edits += edit2
          }

          ???! // SCAN
//          def check(scans: Scans[S], keys: Set[String], isInput: Boolean): Unit = {
//            val toRemove = scans.iterator.collect {
//              case (key, scan) if !keys.contains(key) && scan.isEmpty => key
//            }
//            if (toRemove.nonEmpty) toRemove.foreach { key =>
//              edits += EditRemoveScan(p, key = key, isInput = isInput)
//            }
//
//            val existing = scans.iterator.collect {
//              case (key, _) if keys contains key => key
//            }
//            val toAdd = keys -- existing.toSet
//            if (toAdd.nonEmpty) toAdd.foreach { key =>
//              edits += EditAddScan(p, key = key, isInput = isInput)
//            }
//          }
//
//          val proc = p
//          check(proc.inputs , scanInKeys , isInput = true )
//          check(proc.outputs, scanOutKeys, isInput = false)
        }

        CompoundEdit(edits.result(), editName)

      case _ => None
    }
  }

  def setName[S <: Sys[S]](obj: Obj[S], nameOpt: Option[StringObj[S]])
                          (implicit tx: S#Tx, cursor: stm.Cursor[S]): UndoableEdit = {
    implicit val stringTpe = StringObj
    val edit = EditAttrMap.expr[S, String, StringObj]("Rename Object", obj, ObjKeys.attrName, nameOpt)
//    { ex =>
//      StringObj(StringObj.newVar(ex))
//    }
    edit
  }

  // SCAN
//  def addLink[S <: Sys[S]](sourceKey: String, source: Scan[S], sinkKey: String, sink: Scan[S])
//                          (implicit tx: S#Tx, cursor: stm.Cursor[S]): UndoableEdit = {
//    log(s"Link $sourceKey / $source to $sinkKey / $sink")
//    // source.addSink(Scan.Link.Scan(sink))
//    EditAddScanLink(source = source /* , sourceKey */ , sink = sink /* , sinkKey */)
//  }
//
//  def removeLink[S <: Sys[S]](source: Scan[S], sink: Scan[S])
//                             (implicit tx: S#Tx, cursor: stm.Cursor[S]): UndoableEdit = {
//    log(s"Unlink $source from $sink")
//    // source.removeSink(Scan.Link.Scan(sink))
//    EditRemoveScanLink(source = source /* , sourceKey */ , sink = sink /* , sinkKey */)
//  }

  sealed trait SinkType[S <: Sys[S]]
  final case class SinkDirect[S <: Sys[S]]() extends SinkType[S]
  final case class SinkFolder[S <: Sys[S]](f: Folder[S], index: Int) extends SinkType[S]

  final case class Link[S <: Sys[S]](source: Output[S], sink: Proc[S], key: String, sinkType: SinkType[S])

  def findLink[S <: Sys[S]](out: Proc[S], in: Proc[S], keys: ISeq[String] = Proc.mainIn :: Nil)
                           (implicit tx: S#Tx, cursor: stm.Cursor[S]): Option[Link[S]] = {
    val attr = in.attr
    val it = out.outputs.iterator.flatMap { out =>
      keys.iterator.flatMap { key =>
        val sinkTypeOpt = attr.get(key).flatMap {
          case `out` => Some(SinkDirect[S]())
          case f: Folder[S] =>
            val idx = f.indexOf(out)
            if (idx < 0) None else Some(SinkFolder[S](f, index = idx))
          case _ => None
        }
        sinkTypeOpt.map(tpe => Link(source = out, sink = in, key = key, sinkType = tpe))
      } // .headOption
    }

    if (it.isEmpty) None else Some(it.next()) // XXX TODO -- why no `headOption` on iterator?
  }

  def linkOrUnlink[S <: Sys[S]](out: Proc[S], in: Proc[S])
                               (implicit tx: S#Tx, cursor: stm.Cursor[S]): Option[UndoableEdit] = {
    findLink(out = out, in = in).fold[Option[UndoableEdit]] {
      out.outputs.get(Proc.mainOut).map { out =>
        val key = Proc.mainIn
        in.attr.get(key) match {
          case Some(f: Folder[S]) =>
            val index = f.size
            EditFolderInsertObj("Link", parent = f, index = index, child = out)
          case Some(other) =>
            val f = Folder[S]
            f.addLast(other)
            f.addLast(out)
            EditAttrMap("Add Link", obj = in, key = key, value = Some(f))

          case None =>
            EditAttrMap("Add Link", obj = in, key = key, value = Some(out))
        }
      }
    } { link =>
      val edit = link.sinkType match {
        case SinkDirect() =>
          EditAttrMap("Remove Link", obj = in, key = link.key, value = None)
        case SinkFolder(f, index) =>
          EditFolderRemoveObj("Link", parent = f, index = index, child = link.source)
      }
      Some(edit)
    }
  }

  def resize[S <: Sys[S]](span: SpanLikeObj[S], obj: Obj[S], amount: Resize, minStart: Long)
                         (implicit tx: S#Tx, cursor: stm.Cursor[S]): Option[UndoableEdit] =
    SpanLikeObj.Var.unapply(span).flatMap { vr =>
      import amount.{deltaStart, deltaStop}
      val oldSpan   = span.value
      // val minStart  = timelineModel.bounds.start
      val dStartC   = if (deltaStart >= 0) deltaStart else oldSpan match {
        case Span.HasStart(oldStart) => math.max(-(oldStart - minStart) , deltaStart)
        case _ => 0L
      }
      val dStopC   = if (deltaStop >= 0) deltaStop else oldSpan match {
        case Span.HasStop (oldStop)   => math.max(-(oldStop  - minStart + 32 /* MinDur */), deltaStop)
        case _ => 0L
      }

      if (dStartC != 0L || dStopC != 0L) {
        // val imp = ExprImplicits[S]

        val (dStartCC, dStopCC) = (dStartC, dStopC)

        // XXX TODO -- the variable contents should ideally be looked at
        // during the edit performance

        val oldSpan = vr()
        val newSpan = oldSpan.value match {
          case Span.From (start)  => Span.From (start + dStartCC)
          case Span.Until(stop )  => Span.Until(stop  + dStopCC )
          case Span(start, stop)  =>
            val newStart = start + dStartCC
            Span(newStart, math.max(newStart + 32 /* MinDur */, stop + dStopCC))
          case other => other
        }

        import de.sciss.equal.Implicits._
        val newSpanEx = SpanLikeObj.newConst[S](newSpan)
        if (newSpanEx === oldSpan) None else {
          val name  = "Resize"
          implicit val spanLikeTpe = SpanLikeObj
          val edit0 = EditVar.Expr[S, SpanLike, SpanLikeObj](name, vr, newSpanEx)
          val edit1Opt: Option[UndoableEdit] = if (dStartCC == 0L) None else obj match {
            case objT: Proc[S] =>
              for {
                audioCue <- ProcActions.getAudioRegion(objT)
              } yield {
                // Crazy heuristics
                audioCue match {
                  case AudioCue.Obj.Shift(peer, amt) =>
                    import expr.Ops._
                    amt match {
                      case LongObj.Var(amtVr) =>
                        implicit val longObj = LongObj
                        EditVar.Expr[S, Long, LongObj](name, amtVr, amtVr() + dStartCC)
                      case _ =>
                        val newCue = AudioCue.Obj.Shift(peer, LongObj.newVar[S](amt + dStartCC))
                        EditAttrMap(name, objT, Proc.graphAudio, Some(newCue))
                    }
                  case other =>
                    val newCue = AudioCue.Obj.Shift(other, LongObj.newVar[S](dStartCC))
                    EditAttrMap(name, objT, Proc.graphAudio, Some(newCue))
                }
              }
            case _ => None
          }
          CompoundEdit(edit0 :: edit1Opt.toList, name)
        }
      } else None
    }

  def move[S <: Sys[S]](span: SpanLikeObj[S], obj: Obj[S], amount: Move, minStart: Long)
                         (implicit tx: S#Tx, cursor: stm.Cursor[S]): Option[UndoableEdit] = {
    var edits = List.empty[UndoableEdit]

    import amount._
    if (deltaTrack != 0) {
      // in the case of const, just overwrite, in the case of
      // var, check the value stored in the var, and update the var
      // instead (recursion). otherwise, it will be some combinatorial
      // expression, and we could decide to construct a binary op instead!
      // val expr = ExprImplicits[S]

      import expr.Ops._
      val newTrack: IntObj[S] = obj.attr.$[IntObj](TimelineObjView.attrTrackIndex) match {
        case Some(IntObj.Var(vr)) => vr() + deltaTrack
        case other => other.fold(0)(_.value) + deltaTrack
      }
      import de.sciss.equal.Implicits._
      val newTrackOpt = if (newTrack === IntObj.newConst[S](0)) None else Some(newTrack)
      implicit val intTpe = IntObj
      val edit = EditAttrMap.expr[S, Int, IntObj]("Adjust Track Placement", obj,
        TimelineObjView.attrTrackIndex, newTrackOpt)
//      { ex =>
//          IntObj(IntObj.newVar(ex))
//        }
      edits ::= edit
    }

    val oldSpan   = span.value
    // val minStart  = canvas.timelineModel.bounds.start
    val deltaC    = if (deltaTime >= 0) deltaTime else oldSpan match {
      case Span.HasStart(oldStart)  => math.max(-(oldStart - minStart)         , deltaTime)
      case Span.HasStop (oldStop)   => math.max(-(oldStop  - minStart + 32 /* MinDur */), deltaTime)
      case _                        => 0  // e.g., Span.All
    }
    val name  = "Move"
    if (deltaC != 0L) {
      // val imp = ExprImplicits[S]
      span match {
        case SpanLikeObj.Var(vr) =>
          // s.transform(_ shift deltaC)
          import expr.Ops._
          val newSpan = vr() shift deltaC
          implicit val spanLikeTpe = SpanLikeObj
          val edit    = EditVar.Expr[S, SpanLike, SpanLikeObj](name, vr, newSpan)
          edits ::= edit
        case _ =>
      }
    }

    CompoundEdit(edits, name)
  }

  def unlinkAndRemove[S <: Sys[S]](timeline: proc.Timeline.Modifiable[S], span: SpanLikeObj[S], obj: Obj[S])
                                  (implicit tx: S#Tx, cursor: stm.Cursor[S]): UndoableEdit = {
    val scanEdits = obj match {
      case objT: Proc[S] =>
//        val proc  = objT
//        // val scans = proc.scans
//        val edits1 = proc.inputs.iterator.toList.flatMap { case (key, scan) =>
//          scan.iterator.collect {
//            case Scan.Link.Scan(source) =>
//              removeLink(source, scan)
//          }.toList
//        }
//        val edits2 = proc.outputs.iterator.toList.flatMap { case (key, scan) =>
//          scan.iterator.collect {
//            case Scan.Link.Scan(sink) =>
//              removeLink(scan, sink)
//          } .toList
//        }
//        edits1 ++ edits2
        ???! // SCAN

      case _ => Nil
    }
    val objEdit = EditTimelineRemoveObj("Object", timeline, span, obj)
    CompoundEdit(scanEdits :+ objEdit, "Remove Object").get // XXX TODO - not nice, `get`
  }
}