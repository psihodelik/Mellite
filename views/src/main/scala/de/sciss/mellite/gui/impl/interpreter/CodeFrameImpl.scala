/*
 *  CodeFrameImpl.scala
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
package impl
package interpreter

import javax.swing.event.{AncestorEvent, AncestorListener}
import javax.swing.undo.UndoableEdit

import de.sciss.desktop.impl.UndoManagerImpl
import de.sciss.desktop.{OptionPane, UndoManager}
import de.sciss.lucre.stm
import de.sciss.lucre.stm.{IDPeek, Obj}
import de.sciss.lucre.swing.edit.EditVar
import de.sciss.lucre.swing.{CellView, View}
import de.sciss.lucre.synth.Sys
import de.sciss.synth.SynthGraph
import de.sciss.synth.proc.impl.ActionImpl
import de.sciss.synth.proc.{Action, Code, Proc, SynthGraphObj}

import scala.swing.{Component, Orientation, SplitPane}

object CodeFrameImpl {
  // ---- adapter for editing a Proc's source ----

  def proc[S <: Sys[S]](obj: Proc[S])
                       (implicit tx: S#Tx, workspace: Workspace[S], cursor: stm.Cursor[S],
                        compiler: Code.Compiler): CodeFrame[S] = {
    val codeObj = mkSource(obj = obj, codeID = Code.SynthGraph.id, key = Proc.attrSource,
      init = {
        val txt     = ProcActions.extractSource(obj.graph.value)
        val comment = if (txt.isEmpty)
            "graph function source code"
          else
            "source code automatically extracted"
        s"// $comment\n\n$txt"
      })
    
    val codeEx0 = codeObj
    val objH    = tx.newHandle(obj)
    val code0   = codeEx0.value match {
      case cs: Code.SynthGraph => cs
      case other => sys.error(s"Proc source code does not produce SynthGraph: ${other.contextName}")
    }

    val handler = new CodeView.Handler[S, Unit, SynthGraph] {
      def in() = ()

      def save(in: Unit, out: SynthGraph)(implicit tx: S#Tx): UndoableEdit = {
        val obj = objH()
        implicit val sgTpe = SynthGraphObj
        EditVar.Expr[S, SynthGraph, SynthGraphObj]("Change SynthGraph", obj.graph, SynthGraphObj.newConst[S](out))
      }

      def dispose()(implicit tx: S#Tx) = ()
      def execute()(implicit tx: S#Tx) = ()
    }

    implicit val undo = new UndoManagerImpl
    val rightView = OutputsView[S](obj) // SCANS

    make(obj, codeObj, code0, Some(handler), hasExecute = false, rightViewOpt = Some(rightView))
  }

  // ---- adapter for editing a Action's source ----

  def action[S <: Sys[S]](obj: Action[S])
                         (implicit tx: S#Tx, workspace: Workspace[S], cursor: stm.Cursor[S],
                          compiler: Code.Compiler): CodeFrame[S] = {
    val codeObj = mkSource(obj = obj, codeID = Code.Action.id, key = Action.attrSource,
      init = "// action source code\n\n")

    val codeEx0 = codeObj
    val code0   = codeEx0.value match {
      case cs: Code.Action => cs
      case other => sys.error(s"Action source code does not produce plain function: ${other.contextName}")
    }

    val handlerOpt = obj match {
      case Action.Var(vr) =>
        val varH  = tx.newHandle(vr)
        val objH  = tx.newHandle(obj)
        val handler = new CodeView.Handler[S, String, Array[Byte]] {
          def in(): String = cursor.step { implicit tx =>
            val id = tx.newID()
            val cnt = IDPeek(id)
            s"Action$cnt"
          }

          def save(in: String, out: Array[Byte])(implicit tx: S#Tx): UndoableEdit = {
            val obj = varH()
            val value = ActionImpl.newConst[S](name = in, jar = out)
            EditVar[S, Action[S], Action.Var[S]](name = "Change Action Body", expr = obj, value = value)
          }

          def execute()(implicit tx: S#Tx): Unit = {
            val obj       = objH()
            val universe  = Action.Universe(obj, workspace)
            obj.execute(universe)
            // ActionImpl.execute[S](name = in, jar = out)
          }

          def dispose()(implicit tx: S#Tx) = ()
        }
        Some(handler)

      case _ => None
    }

    implicit val undo = new UndoManagerImpl
    make(obj, codeObj, code0, handlerOpt, hasExecute = true, rightViewOpt = None)
  }

  // ---- general constructor ----

  def apply[S <: Sys[S]](obj: Code.Obj[S], hasExecute: Boolean)
                        (implicit tx: S#Tx, workspace: Workspace[S], cursor: stm.Cursor[S],
                         compiler: Code.Compiler): CodeFrame[S] = {
    val _codeEx = obj
    val _code   = _codeEx.value
    implicit val undo = new UndoManagerImpl
    make[S, _code.In, _code.Out](obj, obj, _code, None, hasExecute = hasExecute, rightViewOpt = None)
  }
  
  private def make[S <: Sys[S], In0, Out0](pObj: Obj[S], obj: Code.Obj[S], code0: Code { type In = In0; type Out = Out0 },
                                handler: Option[CodeView.Handler[S, In0, Out0]], hasExecute: Boolean,
                                rightViewOpt: Option[View[S]])
                               (implicit tx: S#Tx, ws: Workspace[S], csr: stm.Cursor[S],
                                undoMgr: UndoManager, compiler: Code.Compiler): CodeFrame[S] = {
    // val _name   = /* title getOrElse */ obj.attr.name
    val codeView  = CodeView(obj, code0, hasExecute = hasExecute)(handler)
    val view      = rightViewOpt.fold[View[S]](codeView) { bottomView =>
      new View.Editable[S] with ViewHasWorkspace[S] {
        val undoManager = undoMgr
        val cursor      = csr
        val workspace   = ws

        lazy val component: Component = {
          val res = new SplitPane(Orientation.Vertical, codeView.component, bottomView.component)
          res.oneTouchExpandable  = true
          res.resizeWeight        = 1.0
          // cf. https://stackoverflow.com/questions/4934499
          res.peer.addAncestorListener(new AncestorListener {
            def ancestorAdded  (e: AncestorEvent): Unit = res.dividerLocation = 1.0
            def ancestorRemoved(e: AncestorEvent): Unit = ()
            def ancestorMoved  (e: AncestorEvent): Unit = ()
          })
          res
        }

        def dispose()(implicit tx: S#Tx): Unit = {
          codeView  .dispose()
          bottomView.dispose()
        }
      }
    }
    val _name = AttrCellView.name(pObj)
    val res = new FrameImpl(codeView = codeView, view = view, name = _name, contextName = code0.contextName)
    res.init()
    res
  }

  // ---- util ----

  private def mkSource[S <: Sys[S]](obj: Obj[S], codeID: Int, key: String, init: => String)
                                   (implicit tx: S#Tx): Code.Obj[S] = {
    // if there is no source code attached,
    // create a new code object and add it to the attribute map.
    // let's just do that without undo manager
    val codeObj = obj.attr.get(key) match {
      case Some(c: Code.Obj[S]) => c
      case _ =>
        val source  = init
        val code    = Code(codeID, source)
        val c       = Code.Obj.newVar(Code.Obj.newConst[S](code))
        obj.attr.put(key, c)
        c
    }
    codeObj
  }

  // ---- frame impl ----

  private final class FrameImpl[S <: Sys[S]](val codeView: CodeView[S], val view: View[S],
                                             name: CellView[S#Tx, String], contextName: String)
    extends WindowImpl[S](name.map(n => s"$n : $contextName Code")) with CodeFrame[S] {

    override protected def checkClose(): Boolean = {
      if (codeView.isCompiling) {
        // ggStatus.text = "busy!"
        return false
      }

      !codeView.dirty || {
        val message = "The code has been edited.\nDo you want to save the changes?"
        val opt = OptionPane.confirmation(message = message, optionType = OptionPane.Options.YesNoCancel,
          messageType = OptionPane.Message.Warning)
        opt.title = s"Close - $title"
        opt.show(Some(window)) match {
          case OptionPane.Result.No => true
          case OptionPane.Result.Yes =>
            /* val fut = */ codeView.save()
            true

          case OptionPane.Result.Cancel | OptionPane.Result.Closed =>
            false
        }
      }
    }

    // override def style = Window.Auxiliary
  }
}