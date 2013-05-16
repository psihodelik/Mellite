package de.sciss
package mellite
package gui

import desktop.Window
import de.sciss.synth.proc.{AuralSystem, Sys}
import impl.{TimelineFrameImpl => Impl}
import lucre.stm

object TimelineFrame {
  def apply[S <: Sys[S]](document: Document[S], group: Element.ProcGroup[S])
                        (implicit tx: S#Tx, cursor: stm.Cursor[S],
                         aural: AuralSystem): TimelineFrame[S] =
    Impl(document, group)
}
trait TimelineFrame[S <: Sys[S]] {
  def window: Window
  def view: TimelineView[S]
}