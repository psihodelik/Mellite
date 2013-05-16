package de.sciss
package mellite
package gui
package impl

import de.sciss.synth.proc.{AuralSystem, Sys}
import desktop.impl.WindowImpl
import desktop.Window
import lucre.stm

object TimelineFrameImpl {
  def apply[S <: Sys[S]](document: Document[S], group: Element.ProcGroup[S])
                        (implicit tx: S#Tx, cursor: stm.Cursor[S],
                         aural: AuralSystem): TimelineFrame[S] = {
    val tlv   = TimelineView(document, group)
    val name  = group.name.value
    val res   = new Impl(tlv, name)
    guiFromTx {
      res.init()
    }
    res
  }

  private final class Impl[S <: Sys[S]](val view: TimelineView[S], name: String)
    extends TimelineFrame[S] {

    private var _window: Window = _

    def window = _window

    def init() {
      _window = new WindowImpl {
        def handler = Mellite.windowHandler
        def style   = Window.Regular
        component.peer.getRootPane.putClientProperty("apple.awt.brushMetalLook", true)
        title       = name
        contents    = view.component

        bindMenus(
          "file.bounce"           -> view.bounceAction,
          "edit.delete"           -> view.deleteAction,
          "timeline.splitObjects" -> view.splitObjectsAction
        )

        pack()
        // centerOnScreen()
        GUI.placeWindow(this, 0f, 0.25f, 24)
        front()
      }
    }
  }
}