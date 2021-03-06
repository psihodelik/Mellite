/*
 *  MapViewLike.scala
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

import java.awt.datatransfer.Transferable
import java.awt.event.MouseEvent
import java.util.EventObject
import javax.swing.TransferHandler.TransferSupport
import javax.swing.table.{AbstractTableModel, DefaultTableCellRenderer, TableCellEditor}
import javax.swing.undo.UndoableEdit
import javax.swing.{AbstractCellEditor, JComponent, JTable, TransferHandler}

import de.sciss.desktop.{OptionPane, UndoManager, Window}
import de.sciss.lucre.stm
import de.sciss.lucre.stm.{Disposable, Obj, TxnLike}
import de.sciss.lucre.swing._
import de.sciss.lucre.swing.impl.ComponentHolder
import de.sciss.lucre.synth.Sys
import de.sciss.model.impl.ModelImpl
import de.sciss.swingplus.DropMode

import scala.annotation.switch
import scala.collection.breakOut
import scala.collection.immutable.{IndexedSeq => Vec}
import scala.concurrent.stm.TMap
import scala.swing.Swing._
import scala.swing.event.TableRowsSelected
import scala.swing.{Component, Label, ScrollPane, Table, TextField}

abstract class MapViewImpl[S <: Sys[S], Repr](list0: Vec[(String, ListObjView[S])])
                              (implicit val cursor: stm.Cursor[S],
                                        val workspace: Workspace[S], val undoManager: UndoManager)
  extends MapView[S, Repr] with ComponentHolder[Component] with ModelImpl[MapView.Update[S, Repr]] {
  impl: Repr =>

  // ---- abstract ----

  protected def observer: Disposable[S#Tx]

  protected def editImport(key: String, value: Obj[S], isInsert: Boolean)(implicit tx: S#Tx): Option[UndoableEdit]

  protected def editRenameKey(before: String, now: String, value: Obj[S])(implicit tx: S#Tx): Option[UndoableEdit]

  protected def guiInit1(scroll: ScrollPane): Unit

  // ---- impl ----

  protected def showKeyOnly: Boolean = false
  protected def keyEditable: Boolean = true

  private[this] var modelEDT = list0

  private[this] var _table: Table = _
  private[this] var _scroll: ScrollPane = _

  private[this] val viewMap = TMap(list0: _*)

  final protected def table : Table      = _table
  final protected def scroll: ScrollPane = _scroll

  final protected def model: Vec[(String, ListObjView[S])] = {
    requireEDT()
    modelEDT
  }

  def dispose()(implicit tx: S#Tx): Unit = {
    import TxnLike.peer
    observer.dispose()
    viewMap.foreach(_._2.dispose())
    viewMap.clear()
  }

  // helper method that executes model updates on the EDT
  private[this] def withRow(key: String)(fun: Int => Unit)(implicit tx: S#Tx): Unit = deferTx {
    import de.sciss.equal.Implicits._
    val row = modelEDT.indexWhere(_._1 === key)
    if (row < 0) {
      warnNoView(key)
    } else {
      fun(row)
    }
  }

  private[this] def mkValueView(key: String, value: Obj[S])(implicit tx: S#Tx): ListObjView[S] = {
    val view = ListObjView(value)
    viewMap.put(key, view)(tx.peer).foreach(_.dispose())
    view.react { implicit tx => {
      case ObjView.Repaint(_) =>
        withRow(key) { row =>
          tableModel.fireTableRowsUpdated(row, row)
        }
    }}
    view
  }

  final protected def attrAdded(key: String, value: Obj[S])(implicit tx: S#Tx): Unit = {
    val view = mkValueView(key, value)
    deferTx {
      val row = modelEDT.size
      modelEDT :+= key -> view
      tableModel.fireTableRowsInserted(row, row)
    }
  }

  final protected def attrRemoved(key: String)(implicit tx: S#Tx): Unit = {
    viewMap.remove(key)(tx.peer).foreach(_.dispose())
    withRow(key) { row =>
      modelEDT = modelEDT.patch(row, Nil, 1)
      tableModel.fireTableRowsDeleted(row, row)
    }
  }

  final protected def attrReplaced(key: String, before: Obj[S], now: Obj[S])(implicit tx: S#Tx): Unit = {
    val view = mkValueView(key, now)
    withRow(key) { row =>
      modelEDT = modelEDT.patch(row, (key -> view) :: Nil, 1)
      tableModel.fireTableRowsUpdated(row, row)
    }
  }

  private[this] def warnNoView(key: String): Unit = println(s"Warning: AttrMapView - no view found for $key")

  private[this] object tableModel extends AbstractTableModel {
    def getRowCount   : Int = modelEDT.size
    def getColumnCount: Int = if (showKeyOnly) 1 else 3

    override def getColumnName(col: Int): String = (col: @switch) match {
      case 0 => "Key"
      case 1 => "Name"
      case 2 => "Value"
    }

    def getValueAt(row /* rowView */: Int, col: Int): AnyRef = {
      // val row = tab.peer.convertRowIndexToModel(rowView)
      (col: @switch) match {
        case 0 => modelEDT(row)._1
        case 1 => modelEDT(row)._2 // .name
        case 2 => modelEDT(row)._2 // .value.asInstanceOf[AnyRef]
      }
    }

    override def getColumnClass(col: Int): Class[_] = (col: @switch) match {
      case 0 => classOf[String]
      case 1 => classOf[ObjView[S]]
      case 2 => classOf[ObjView[S]]
    }

    override def isCellEditable(row: Int, col: Int): Boolean = {
      val res = if (col == 0) keyEditable else modelEDT(row)._2.isEditable
      // println(s"isCellEditable(row = $row, col = $col) -> $res")
      res
    }

    // println(s"setValueAt(value = $value, row = $row, col = $col")
    override def setValueAt(editValue: Any, row: Int, col: Int): Unit = (col: @switch) match {
      case 0 =>
        val (oldKey, view) = modelEDT(row)
        val newKey  = editValue.toString
        if (oldKey != newKey) {
          val editOpt = cursor.step { implicit tx =>
            val value = view.obj
            editRenameKey(before = oldKey, now = newKey, value = value)
          }
          editOpt.foreach(undoManager.add)
        }

      case 2 =>
        val view    = modelEDT(row)._2
        val editOpt = cursor.step { implicit tx => view.tryEdit(editValue) }
        editOpt.foreach(undoManager.add)

      case _ =>
    }
  }

  final protected def guiInit(): Unit = {
    _table = new Table {
      // Table default has idiotic renderer/editor handling
      override lazy val peer: JTable = new JTable /* with Table.JTableMixin */ with SuperMixin
    }
    _table.model     = tableModel
    val jt        = _table.peer
    jt.setAutoCreateRowSorter(true)
    val tcm       = jt.getColumnModel
    val colName   = tcm.getColumn(0)
    colName .setPreferredWidth( 96)

    if (!showKeyOnly) {
      val colTpe    = tcm.getColumn(1)
      val colValue  = tcm.getColumn(2)
      colTpe  .setPreferredWidth( 96)
      colValue.setPreferredWidth(208)

      colTpe.setCellRenderer(new DefaultTableCellRenderer {
        outer =>
        private val wrap = new Label { override lazy val peer = outer }

        override def setValue(value: Any): Unit = value match {
          case view: ObjView[_] =>
            import de.sciss.equal.Implicits._
            wrap.text = if (view.name === "<unnamed>") "" else view.name
            wrap.icon = view.icon
          case _ =>
        }
      })
      colValue.setCellRenderer(new DefaultTableCellRenderer {
        outer =>
        private val wrap = new Label { override lazy val peer = outer }
        override def getTableCellRendererComponent(table: JTable, value: Any, isSelected: Boolean,
                                                   hasFocus: Boolean, row: Int, column: Int): java.awt.Component = {
          super.getTableCellRendererComponent(table, null, isSelected, hasFocus, row, column)
          if (getIcon != null) setIcon(null)
          value match {
            case view: ListObjView[_] => view.configureRenderer(wrap).peer
            case _ => outer
          }
        }
      })
      colValue.setCellEditor(new AbstractCellEditor with TableCellEditor {
        // private var currentValue: Any = null
        private val editor = new TextField(10)

        override def isCellEditable(e: EventObject): Boolean = e match {
          case m: MouseEvent => m.getClickCount >= 2
          case _ => true
        }

        def getCellEditorValue: AnyRef = editor.text // currentValue.asInstanceOf[AnyRef]

        def getTableCellEditorComponent(table: JTable, value: Any, isSelected: Boolean, rowV: Int,
                                        col: Int): java.awt.Component = {
          val row = table.convertRowIndexToModel(rowV)
          val view      = modelEDT(row)._2
          // currentValue  = view.value
          editor.text   = view.value.toString
          editor.peer
        }
      })
    }

    jt.setPreferredScrollableViewportSize((if (showKeyOnly) 130 else 390) -> 160)

    import de.sciss.swingplus.Implicits._
    _table.sort(0)

    jt.setDragEnabled(true)
    jt.setDropMode(DropMode.OnOrInsertRows)
    jt.setTransferHandler(new TransferHandler {
      override def getSourceActions(c: JComponent): Int = TransferHandler.LINK

      override def createTransferable(c: JComponent): Transferable = {
        val sel     = selection
        val trans1 = if (sel.size == 1) {
          val _res = DragAndDrop.Transferable(ListObjView.Flavor) {
            ListObjView.Drag(workspace, sel.head._2)
          }
          _res
        } else null

        trans1
      }

      override def canImport(support: TransferSupport): Boolean = {
        val res = support.isDrop && {
          val dl = support.getDropLocation.asInstanceOf[JTable.DropLocation]
          val locOk = dl.isInsertRow || {
            val viewCol   = dl.getColumn
            val modelCol  = jt.convertColumnIndexToModel(viewCol)
            modelCol >= 1   // should drop on the 'type' or 'value' column
          }
          // println(s"locOk? $locOk")
          val allOk = locOk && support.isDataFlavorSupported(ListObjView.Flavor)
          if (allOk) support.setDropAction(TransferHandler.LINK)
          allOk
        }
        res
      }

      override def importData(support: TransferSupport): Boolean = {
        val res = support.isDrop && {
          val dl        = support.getDropLocation.asInstanceOf[JTable.DropLocation]
          val isInsert  = dl.isInsertRow
          val view      = support.getTransferable.getTransferData(ListObjView.Flavor).asInstanceOf[ListObjView.Drag[S]].view
          val keyOpt = if (isInsert) { // ---- create new entry with key via dialog ----
            queryKey("key")
          } else {          // ---- update value of existing entrywith key via dialog ----
          val rowV  = dl.getRow
            val row   = jt.convertRowIndexToModel(rowV)
            Some(modelEDT(row)._1)
          }
          // println(s"TODO: ${if (isInsert) "insert" else "replace"} $view")

          keyOpt.exists { key =>
            val editOpt = cursor.step { implicit tx =>
              editImport(isInsert = isInsert, key = key, value = view.obj)
            }
            editOpt.foreach(undoManager.add)
            editOpt.isDefined
          }
        }
        res
      }
    })

    _scroll = new ScrollPane(_table)
    _scroll.peer.putClientProperty("styleId", "undecorated")
    _scroll.border = null
    // component     = scroll
    _table.listenTo(_table.selection)
    _table.reactions += {
      case TableRowsSelected(_, _, false) => // note: range is range of _changes_ rows, not current selection
        val sel = selection
        dispatch(MapView.SelectionChanged(impl, sel))
    }
    guiInit1(_scroll)
  }

  final def queryKey(initial: String): Option[String] = {
    val opt   = OptionPane.textInput(message = "Key Name", initial = initial)
    opt.title = "Create Attribute"
    opt.show(Window.find(component))
  }

  final def selection: List[(String, ObjView[S])] = {
    val ind0: List[Int] = _table.selection.rows.map(_table.peer.convertRowIndexToModel)(breakOut)
    val indices = ind0.sorted
    indices.map(modelEDT.apply)
  }
}
