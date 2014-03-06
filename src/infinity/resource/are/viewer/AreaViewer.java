// Near Infinity - An Infinity Engine Browser and Editor
// Copyright (C) 2001 - 2005 Jon Olav Hauglid
// See LICENSE.txt for license information

package infinity.resource.are.viewer;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JTextArea;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import javax.swing.JViewport;
import javax.swing.ProgressMonitor;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

import infinity.NearInfinity;
import infinity.datatype.Flag;
import infinity.datatype.ResourceRef;
import infinity.gui.ButtonPopupWindow;
import infinity.gui.Center;
import infinity.gui.ChildFrame;
import infinity.gui.RenderCanvas;
import infinity.gui.StructViewer;
import infinity.gui.ViewFrame;
import infinity.gui.WindowBlocker;
import infinity.gui.layeritem.AbstractLayerItem;
import infinity.gui.layeritem.IconLayerItem;
import infinity.gui.layeritem.LayerItemEvent;
import infinity.gui.layeritem.LayerItemListener;
import infinity.icon.Icons;
import infinity.resource.AbstractStruct;
import infinity.resource.Resource;
import infinity.resource.ResourceFactory;
import infinity.resource.are.AreResource;
import infinity.resource.are.RestSpawn;
import infinity.resource.are.Song;
import infinity.resource.are.viewer.ViewerConstants.LayerStackingType;
import infinity.resource.are.viewer.ViewerConstants.LayerType;
import infinity.resource.key.BIFFResourceEntry;
import infinity.resource.key.ResourceEntry;
import infinity.resource.wed.Overlay;
import infinity.resource.wed.WedResource;
import infinity.util.ArrayUtil;
import infinity.util.NIFile;

/**
 * The Area Viewer shows a selected map with its associated structures, such as actors, regions or
 * animations.
 * @author argent77
 */
public class AreaViewer extends ChildFrame
    implements ActionListener, MouseListener, MouseMotionListener, ChangeListener, TilesetChangeListener,
               PropertyChangeListener, LayerItemListener, ComponentListener
{
  private static final String LabelInfoX = "Position X:";
  private static final String LabelInfoY = "Position Y:";
  private static final String LabelEnableSchedule = "Enable time schedules";
  private static final String LabelDrawClosed = "Draw closed";
  private static final String LabelDrawOverlays = "Enable overlays";
  private static final String LabelAnimateOverlays = "Animate overlays";
  private static final String LabelDrawGrid = "Show grid";

  private final Map map;
  private final Point mapCoordinates = new Point();
  private final String windowTitle;
  private final JCheckBox[] cbLayers = new JCheckBox[LayerManager.getLayerTypeCount()];;
  private final JCheckBox[] cbLayerRealAnimation = new JCheckBox[2];
  private final JToggleButton[] tbAddLayerItem = new JToggleButton[LayerManager.getLayerTypeCount()];

  private LayerManager layerManager;
  private TilesetRenderer rcCanvas;
  private JPanel pCanvas;
  private JScrollPane spCanvas;
  private Rectangle vpCenterExtent;   // combines map center and viewport extent in one structure
  private JToolBar toolBar;
  private JToggleButton tbView, tbEdit;
  private JButton tbAre, tbWed, tbSongs, tbRest, tbSettings, tbRefresh;
  private ButtonPopupWindow bpwDayTime;
  private DayTimePanel pDayTime;
  private JCheckBox cbDrawClosed, cbDrawOverlays, cbAnimateOverlays, cbDrawGrid, cbEnableSchedules;
  private JComboBox cbZoomLevel;
  private JCheckBox cbLayerAmbientRange;
  private JLabel lPosX, lPosY;
  private JTextArea taInfo;
  private boolean bMapDragging;
  private Point mapDraggingPosStart, mapDraggingScrollStart, mapDraggingPos;
  private Timer timerOverlays;
  private JPopupMenu pmItems;
  private SwingWorker<Void, Void> workerInitGui, workerLoadMap;
  private ProgressMonitor progress;
  private int pmCur, pmMax;
  private WindowBlocker blocker;


  /**
   * Checks whether the specified ARE resource can be displayed with the area viewer.
   * @param are The ARE resource to check
   * @return <code>true</code> if area is viewable, <code>false</code> otherwise.
   */
  public static boolean IsValid(AreResource are)
  {
    if (are != null) {
      ResourceRef wedRef = (ResourceRef)are.getAttribute("WED resource");
      ResourceEntry wedEntry = ResourceFactory.getInstance().getResourceEntry(wedRef.getResourceName());
      if (wedEntry != null) {
        try {
          WedResource wedFile = new WedResource(wedEntry);
          Overlay overlay = (Overlay)wedFile.getAttribute("Overlay 0");
          ResourceRef tisRef = (ResourceRef)overlay.getAttribute("Tileset");
          ResourceEntry tisEntry = ResourceFactory.getInstance().getResourceEntry(tisRef.getResourceName());
          if (tisEntry != null)
            return true;
        } catch (Exception e) {
        }
      }
    }
    return false;
  }


  public AreaViewer(AreResource are)
  {
    this(NearInfinity.getInstance(), are);
  }

  public AreaViewer(Component parent, AreResource are)
  {
    super("", true);
    windowTitle = String.format("Area Viewer: %1$s", (are != null) ? are.getName() : "[Unknown]");
    initProgressMonitor(parent, "Initializing " + are.getName(), "Loading ARE resource...", 3, 0, 0);
    map = new Map(this, are);
    // loading map in dedicated thread
    workerInitGui = new SwingWorker<Void, Void>() {
      @Override
      protected Void doInBackground() throws Exception {
        try {
          init();
        } catch (Exception e) {
          e.printStackTrace();
        }
        return null;
      }
    };
    workerInitGui.addPropertyChangeListener(this);
    workerInitGui.execute();
  }

  /**
   * Returns the tileset renderer for this viewer instance.
   * @return The currently used TilesetRenderer instance.
   */
  public TilesetRenderer getRenderer()
  {
    return rcCanvas;
  }

//--------------------- Begin Interface ActionListener ---------------------

  @Override
  public void actionPerformed(ActionEvent event)
  {
    if (event.getSource() instanceof JCheckBox) {
      JCheckBox cb = (JCheckBox)event.getSource();
      LayerType layer = getLayerType(cb);
      if (layer != null) {
        showLayer(layer, cb.isSelected());
        if (layer == LayerType.Ambient) {
          // Taking care of local ambient ranges
          updateAmbientRange();
        } else if (layer == LayerType.Animation) {
          // Taking care of real animation display
          updateRealAnimation();
        }
        updateScheduledItems();
      } else if (cb == cbLayerAmbientRange) {
        updateAmbientRange();
      } else if (cb == cbLayerRealAnimation[0]) {
        if (cbLayerRealAnimation[0].isSelected()) {
          cbLayerRealAnimation[1].setSelected(false);
        }
        updateRealAnimation();
      } else if (cb == cbLayerRealAnimation[1]) {
        if (cbLayerRealAnimation[1].isSelected()) {
          cbLayerRealAnimation[0].setSelected(false);
        }
        updateRealAnimation();
      } else if (cb == cbEnableSchedules) {
        WindowBlocker.blockWindow(this, true);
        try {
          Settings.EnableSchedules = cbEnableSchedules.isSelected();
          updateTimeSchedules();
        } finally {
          WindowBlocker.blockWindow(this, false);
        }
      } else if (cb == cbDrawClosed) {
        WindowBlocker.blockWindow(this, true);
        try {
          setDoorState(cb.isSelected());
        } finally {
          WindowBlocker.blockWindow(this, false);
        }
      } else if (cb == cbDrawGrid) {
        WindowBlocker.blockWindow(this, true);
        try {
          setTileGridEnabled(cb.isSelected());
        } finally {
          WindowBlocker.blockWindow(this, false);
        }
      } else if (cb == cbDrawOverlays) {
        WindowBlocker.blockWindow(this, true);
        try {
          setOverlaysEnabled(cb.isSelected());
          cbAnimateOverlays.setEnabled(cb.isSelected());
          if (!cb.isSelected() && cbAnimateOverlays.isSelected()) {
            cbAnimateOverlays.setSelected(false);
            setOverlaysAnimated(false);
          }
        } finally {
          WindowBlocker.blockWindow(this, false);
        }
      } else if (cb == cbAnimateOverlays) {
        WindowBlocker.blockWindow(this, true);
        try {
          setOverlaysAnimated(cb.isSelected());
        } finally {
          WindowBlocker.blockWindow(this, false);
        }
      }
    } else if (event.getSource() == cbZoomLevel) {
      WindowBlocker.blockWindow(this, true);
      try {
        int previousZoomLevel = Settings.ZoomLevel;
        try {
          setZoomLevel(cbZoomLevel.getSelectedIndex());
        } catch (OutOfMemoryError e) {
          cbZoomLevel.hidePopup();
          WindowBlocker.blockWindow(this, false);
          String msg = "Not enough memory to set selected zoom level.\n"
                       + "(Note: It is highly recommended to close and reopen the area viewer.)";
          JOptionPane.showMessageDialog(this, msg, "Error", JOptionPane.ERROR_MESSAGE);
          cbZoomLevel.setSelectedIndex(previousZoomLevel);
          setZoomLevel(previousZoomLevel);
        }
      } finally {
        WindowBlocker.blockWindow(this, false);
      }
    } else if (event.getSource() == timerOverlays) {
      advanceOverlayAnimation();
    } else if (event.getSource() instanceof AbstractLayerItem) {
      AbstractLayerItem item = (AbstractLayerItem)event.getSource();
      showTable(item);
    } else if (event.getSource() instanceof LayerMenuItem) {
      LayerMenuItem lmi = (LayerMenuItem)event.getSource();
      AbstractLayerItem item = lmi.getLayerItem();
      showTable(item);
    } else if (event.getSource() == tbAre) {
      showTable(map.getAreItem());
    } else if (event.getSource() == tbWed) {
      showTable(map.getWedItem(getCurrentWedIndex()));
    } else if (event.getSource() == tbSongs) {
      showTable(map.getSongItem());
    } else if (event.getSource() == tbRest) {
      showTable(map.getRestItem());
    } else if (event.getSource() == tbSettings) {
      viewSettings();
    } else if (event.getSource() == tbRefresh) {
      WindowBlocker.blockWindow(this, true);
      try {
        reloadLayers();
      } finally {
        WindowBlocker.blockWindow(this, false);
      }
    } else if (ArrayUtil.indexOf(tbAddLayerItem, event.getSource()) >= 0) {
      // TODO: include "Add layer item" functionality
      int index = ArrayUtil.indexOf(tbAddLayerItem, event.getSource());
      switch (LayerManager.getLayerType(index)) {
        case Actor:
        case Ambient:
        case Animation:
        case Automap:
        case Container:
        case Door:
        case DoorPoly:
        case Entrance:
        case ProTrap:
        case Region:
        case SpawnPoint:
        case Transition:
        case WallPoly:
          break;
      }
    }
  }

//--------------------- End Interface ActionListener ---------------------

//--------------------- Begin Interface MouseMotionListener ---------------------

  @Override
  public void mouseDragged(MouseEvent event)
  {
    if (event.getSource() == rcCanvas && isMapDragging(event.getLocationOnScreen())) {
      moveMapViewport();
    }
  }

  @Override
  public void mouseMoved(MouseEvent event)
  {
    if (event.getSource() == rcCanvas) {
      showMapCoordinates(event.getPoint());
    } else if (event.getSource() instanceof AbstractLayerItem) {
      AbstractLayerItem item = (AbstractLayerItem)event.getSource();
      MouseEvent newEvent = new MouseEvent(rcCanvas, event.getID(), event.getWhen(), event.getModifiers(),
                                           event.getX() + item.getX(), event.getY() + item.getY(),
                                           event.getXOnScreen(), event.getYOnScreen(),
                                           event.getClickCount(), event.isPopupTrigger(), event.getButton());
      rcCanvas.dispatchEvent(newEvent);
    }
  }

//--------------------- End Interface MouseMotionListener ---------------------

//--------------------- Begin Interface MouseListener ---------------------

  @Override
  public void mouseClicked(MouseEvent event)
  {
  }

  @Override
  public void mousePressed(MouseEvent event)
  {
    if (event.getButton() == MouseEvent.BUTTON1 && event.getSource() == rcCanvas) {
      setMapDraggingEnabled(true, event.getLocationOnScreen());
    } else {
      showItemPopup(event);
    }
  }

  @Override
  public void mouseReleased(MouseEvent event)
  {
    if (event.getButton() == MouseEvent.BUTTON1 && event.getSource() == rcCanvas) {
      setMapDraggingEnabled(false, event.getLocationOnScreen());
    } else {
      showItemPopup(event);
    }
  }

  @Override
  public void mouseEntered(MouseEvent event)
  {
  }

  @Override
  public void mouseExited(MouseEvent event)
  {
  }

//--------------------- End Interface MouseListener ---------------------

//--------------------- Begin Interface ChangeListener ---------------------

  @Override
  public void stateChanged(ChangeEvent event)
  {
    if (event.getSource() == pDayTime) {
      if (workerLoadMap == null) {
        // loading map in a separate thread
        if (workerLoadMap == null) {
          blocker = new WindowBlocker(this);
          blocker.setBlocked(true);
          workerLoadMap = new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception
            {
              setHour(pDayTime.getHour());
              return null;
            }
          };
          workerLoadMap.addPropertyChangeListener(this);
          workerLoadMap.execute();
        }
      }
    } else if (event.getSource() == spCanvas.getViewport()) {
      setViewpointCenter();
    }
  }

//--------------------- End Interface ChangeListener ---------------------

//--------------------- Begin Interface TilesetChangeListener ---------------------

  @Override
  public void tilesetChanged(TilesetChangeEvent event)
  {
    if (event.getSource() == rcCanvas) {
      if (event.hasChangedMap()) {
        updateLayerItems();
      }
    }
  }

//--------------------- End Interface TilesetChangeListener ---------------------

//--------------------- Begin Interface LayerItemListener ---------------------

  @Override
  public void layerItemChanged(LayerItemEvent event)
  {
    if (event.getSource() instanceof AbstractLayerItem) {
      AbstractLayerItem item = (AbstractLayerItem)event.getSource();
      if (event.isHighlighted()) {
        setInfoText(item.getMessage());
      } else {
        setInfoText(null);
      }
    }
  }

//--------------------- End Interface LayerItemListener ---------------------

//--------------------- Begin Interface PropertyChangeListener ---------------------

  @Override
  public void propertyChange(PropertyChangeEvent event)
  {
    if (event.getSource() == workerInitGui) {
      if ("state".equals(event.getPropertyName()) &&
          SwingWorker.StateValue.DONE == event.getNewValue()) {
        releaseProgressMonitor();
        workerInitGui = null;
      }
    } else if (event.getSource() == workerLoadMap) {
      if ("state".equals(event.getPropertyName()) &&
          SwingWorker.StateValue.DONE == event.getNewValue()) {
        if (blocker != null) {
          blocker.setBlocked(false);
          blocker = null;
        }
        releaseProgressMonitor();
        workerLoadMap = null;
      }
    }
  }

//--------------------- End Interface PropertyChangeListener ---------------------

//--------------------- Begin Interface ComponentListener ---------------------

  @Override
  public void componentResized(ComponentEvent event)
  {
    if (event.getSource() == rcCanvas) {
      // changing panel size whenever the tileset size changes
      pCanvas.setPreferredSize(rcCanvas.getSize());
      pCanvas.setSize(rcCanvas.getSize());
    }
    if (event.getSource() == spCanvas) {
      if (isAutoZoom()) {
        setZoomLevel(Settings.ZoomFactorIndexAuto);
      }
      // centering the tileset if it fits into the viewport
      Dimension pDim = rcCanvas.getPreferredSize();
      Dimension spDim = pCanvas.getSize();
      if (pDim.width < spDim.width || pDim.height < spDim.height) {
        Point pLocation = rcCanvas.getLocation();
        Point pDistance = new Point();
        if (pDim.width < spDim.width) {
          pDistance.x = pLocation.x - (spDim.width - pDim.width) / 2;
        }
        if (pDim.height < spDim.height) {
          pDistance.y = pLocation.y - (spDim.height - pDim.height) / 2;
        }
        rcCanvas.setLocation(pLocation.x - pDistance.x, pLocation.y - pDistance.y);
      } else {
        rcCanvas.setLocation(0, 0);
      }
    }
  }

  @Override
  public void componentMoved(ComponentEvent event)
  {
  }

  @Override
  public void componentShown(ComponentEvent event)
  {
  }

  @Override
  public void componentHidden(ComponentEvent event)
  {
  }

//--------------------- End Interface ComponentListener ---------------------

//--------------------- Begin Class ChildFrame ---------------------

  @Override
  protected boolean windowClosing(boolean forced) throws Exception
  {
    Settings.storeSettings(false);

    if (!map.closeWed(Map.MAP_DAY, true)) {
      return false;
    }
    if (!map.closeWed(Map.MAP_NIGHT, true)) {
      return false;
    }
    if (map != null) {
      map.clear();
    }
    if (rcCanvas != null) {
      removeLayerItems();
      rcCanvas.clear();
      rcCanvas.setImage(null);
    }
    if (layerManager != null) {
      layerManager.clear();
      layerManager = null;
    }
    dispose();
    System.gc();
    return super.windowClosing(forced);
  }

//--------------------- End Class ChildFrame ---------------------


  private static GridBagConstraints setGBC(GridBagConstraints gbc, int gridX, int gridY,
                                           int gridWidth, int gridHeight, double weightX, double weightY,
                                           int anchor, int fill, Insets insets, int iPadX, int iPadY)
  {
    if (gbc == null) gbc = new GridBagConstraints();

    gbc.gridx = gridX;
    gbc.gridy = gridY;
    gbc.gridwidth = gridWidth;
    gbc.gridheight = gridHeight;
    gbc.weightx = weightX;
    gbc.weighty = weightY;
    gbc.anchor = anchor;
    gbc.fill = fill;
    gbc.insets = (insets == null) ? new Insets(0, 0, 0, 0) : insets;
    gbc.ipadx = iPadX;
    gbc.ipady = iPadY;

    return gbc;
  }


  // initialize GUI and structures
  private void init()
  {
    advanceProgressMonitor("Initializing GUI...");

    GridBagConstraints c = new GridBagConstraints();
    JPanel p;

    // initialize misc. features
    pmItems = new JPopupMenu("Select item:");
    bMapDragging = false;
    mapDraggingPosStart = new Point();
    mapDraggingPos = new Point();
    mapDraggingScrollStart = new Point();

    // Creating main view area
    pCanvas = new JPanel(new GridBagLayout());
    rcCanvas = new TilesetRenderer();
    rcCanvas.addComponentListener(this);
    rcCanvas.addMouseListener(this);
    rcCanvas.addMouseMotionListener(this);
    rcCanvas.addChangeListener(this);
    rcCanvas.setHorizontalAlignment(RenderCanvas.CENTER);
    rcCanvas.setVerticalAlignment(RenderCanvas.CENTER);
    rcCanvas.setLocation(0, 0);
    rcCanvas.setLayout(null);
    c = setGBC(c, 0, 0, 1, 1, 0.0, 0.0, GridBagConstraints.CENTER,
               GridBagConstraints.BOTH, new Insets(0, 0, 0, 0), 0, 0);
    pCanvas.add(rcCanvas, c);
    spCanvas = new JScrollPane(pCanvas);
    spCanvas.addComponentListener(this);
    spCanvas.getViewport().addChangeListener(this);
    spCanvas.getVerticalScrollBar().setUnitIncrement(16);
    spCanvas.getHorizontalScrollBar().setUnitIncrement(16);
    JPanel pView = new JPanel(new GridBagLayout());
    c = setGBC(c, 0, 0, 1, 1, 1.0, 1.0, GridBagConstraints.CENTER,
               GridBagConstraints.BOTH, new Insets(0, 0, 0, 0), 0, 0);
    pView.add(spCanvas, c);

    // Creating right side bar
    // Creating Visual State area
    bpwDayTime = new ButtonPopupWindow("", Icons.getIcon("ArrowDown15.png"));
    bpwDayTime.setIconTextGap(8);
    pDayTime = new DayTimePanel(bpwDayTime, getHour());
    pDayTime.addChangeListener(this);
    bpwDayTime.setContent(pDayTime);
    bpwDayTime.setMargin(new Insets(4, bpwDayTime.getMargin().left, 4, bpwDayTime.getMargin().right));

    cbEnableSchedules = new JCheckBox(LabelEnableSchedule);
    cbEnableSchedules.setToolTipText("Enable activity schedules on layer structures that support them (e.g. actors, ambient sounds or background animations.");
    cbEnableSchedules.addActionListener(this);

    cbDrawClosed = new JCheckBox(LabelDrawClosed);
    cbDrawClosed.setToolTipText("Draw opened or closed states of doors");
    cbDrawClosed.addActionListener(this);

    cbDrawGrid = new JCheckBox(LabelDrawGrid);
    cbDrawGrid.addActionListener(this);

    cbDrawOverlays = new JCheckBox(LabelDrawOverlays);
    cbDrawOverlays.addActionListener(this);

    String msgAnimate = "Warning: The area viewer may become less responsive when activating this feature.";
    cbAnimateOverlays = new JCheckBox(LabelAnimateOverlays);
    cbAnimateOverlays.setToolTipText(msgAnimate);
    cbAnimateOverlays.addActionListener(this);

    JLabel lZoomLevel = new JLabel("Zoom map:");
    cbZoomLevel = new JComboBox(Settings.LabelZoomFactor);
    cbZoomLevel.setSelectedIndex(Settings.ZoomLevel);
    cbZoomLevel.addActionListener(this);
    JPanel pZoom = new JPanel(new GridBagLayout());
    c = setGBC(c, 0, 0, 1, 1, 0.0, 0.0, GridBagConstraints.LINE_START,
               GridBagConstraints.NONE, new Insets(0, 0, 0, 0), 0, 0);
    pZoom.add(lZoomLevel, c);
    c = setGBC(c, 1, 0, 1, 1, 0.0, 0.0, GridBagConstraints.LINE_START,
               GridBagConstraints.HORIZONTAL, new Insets(0, 8, 0, 0), 0, 0);
    pZoom.add(cbZoomLevel, c);

    p = new JPanel(new GridBagLayout());
    c = setGBC(c, 0, 0, 1, 1, 1.0, 0.0, GridBagConstraints.FIRST_LINE_START,
               GridBagConstraints.HORIZONTAL, new Insets(4, 0, 0, 0), 0, 0);
    p.add(bpwDayTime, c);
    c = setGBC(c, 0, 1, 1, 1, 1.0, 0.0, GridBagConstraints.FIRST_LINE_START,
               GridBagConstraints.NONE, new Insets(8, 0, 0, 0), 0, 0);
    p.add(cbEnableSchedules, c);
    c = setGBC(c, 0, 2, 1, 1, 1.0, 0.0, GridBagConstraints.FIRST_LINE_START,
               GridBagConstraints.NONE, new Insets(4, 0, 0, 0), 0, 0);
    p.add(cbDrawClosed, c);
    c = setGBC(c, 0, 3, 1, 1, 1.0, 0.0, GridBagConstraints.FIRST_LINE_START,
               GridBagConstraints.NONE, new Insets(4, 0, 0, 0), 0, 0);
    p.add(cbDrawGrid, c);
    c = setGBC(c, 0, 4, 1, 1, 1.0, 0.0, GridBagConstraints.FIRST_LINE_START,
               GridBagConstraints.NONE, new Insets(4, 0, 0, 0), 0, 0);
    p.add(cbDrawOverlays, c);
    c = setGBC(c, 0, 5, 1, 1, 1.0, 0.0, GridBagConstraints.FIRST_LINE_START,
               GridBagConstraints.NONE, new Insets(0, 12, 0, 0), 0, 0);
    p.add(cbAnimateOverlays, c);
    c = setGBC(c, 0, 6, 1, 1, 1.0, 0.0, GridBagConstraints.FIRST_LINE_START,
               GridBagConstraints.NONE, new Insets(4, 4, 0, 0), 0, 0);
    p.add(pZoom, c);

    JPanel pVisualState = new JPanel(new GridBagLayout());
    pVisualState.setBorder(BorderFactory.createTitledBorder("Visual State: "));
    c = setGBC(c, 0, 0, 1, 1, 1.0, 0.0, GridBagConstraints.FIRST_LINE_START,
               GridBagConstraints.BOTH, new Insets(0, 4, 4, 4), 0, 0);
    pVisualState.add(p, c);


    // Creating Layers area
    p = new JPanel(new GridBagLayout());
    for (int idx = 0, i = 0; i < LayerManager.getLayerTypeCount(); i++, idx++) {
      LayerType layer = LayerManager.getLayerType(i);
      cbLayers[i] = new JCheckBox(LayerManager.getLayerTypeLabel(layer));
      cbLayers[i].addActionListener(this);
      c = setGBC(c, 0, idx, 1, 1, 1.0, 0.0, GridBagConstraints.FIRST_LINE_START,
                 GridBagConstraints.NONE, new Insets(0, 0, 0, 0), 0, 0);
      p.add(cbLayers[i], c);
      if (i == LayerManager.getLayerTypeIndex(LayerType.Ambient)) {
        // Initializing ambient sound range checkbox
        cbLayerAmbientRange = new JCheckBox("Show local sound ranges");
        cbLayerAmbientRange.addActionListener(this);
        idx++;
        c = setGBC(c, 0, idx, 1, 1, 1.0, 0.0, GridBagConstraints.FIRST_LINE_START,
                   GridBagConstraints.NONE, new Insets(0, 12, 0, 0), 0, 0);
        p.add(cbLayerAmbientRange, c);
      } else if (i == LayerManager.getLayerTypeIndex(LayerType.Animation)) {
        // Initializing real animation checkboxes
        cbLayerRealAnimation[0] = new JCheckBox("Show actual animations");
        cbLayerRealAnimation[0].addActionListener(this);
        cbLayerRealAnimation[1] = new JCheckBox("Animate actual animations");
        cbLayerRealAnimation[1].setToolTipText(msgAnimate);
        cbLayerRealAnimation[1].addActionListener(this);
        idx++;
        c = setGBC(c, 0, idx, 1, 1, 1.0, 0.0, GridBagConstraints.FIRST_LINE_START,
            GridBagConstraints.NONE, new Insets(0, 12, 0, 0), 0, 0);
        p.add(cbLayerRealAnimation[0], c);
        idx++;
        c = setGBC(c, 0, idx, 1, 1, 1.0, 0.0, GridBagConstraints.FIRST_LINE_START,
                   GridBagConstraints.NONE, new Insets(0, 12, 0, 0), 0, 0);
        p.add(cbLayerRealAnimation[1], c);
      }
    }

    JPanel pLayers = new JPanel(new GridBagLayout());
    pLayers.setBorder(BorderFactory.createTitledBorder("Layers: "));
    c = setGBC(c, 0, 0, 1, 1, 1.0, 0.0, GridBagConstraints.FIRST_LINE_START,
               GridBagConstraints.BOTH, new Insets(0, 4, 0, 4), 0, 0);
    pLayers.add(p, c);


    // Creating Info Box area
    JLabel lPosXLabel = new JLabel(LabelInfoX);
    JLabel lPosYLabel = new JLabel(LabelInfoY);
    lPosX = new JLabel("0");
    lPosY = new JLabel("0");
    taInfo = new JTextArea(4, 15);
    taInfo.setEditable(false);
    taInfo.setFont(lPosX.getFont());
    taInfo.setBackground(lPosX.getBackground());
    taInfo.setSelectionColor(lPosX.getBackground());
    taInfo.setSelectedTextColor(lPosX.getBackground());
    taInfo.setWrapStyleWord(true);
    taInfo.setLineWrap(true);

    p = new JPanel(new GridBagLayout());
    c = setGBC(c, 0, 0, 1, 1, 0.0, 0.0, GridBagConstraints.FIRST_LINE_START,
               GridBagConstraints.NONE, new Insets(0, 0, 0, 0), 0, 0);
    p.add(lPosXLabel, c);
    c = setGBC(c, 1, 0, 1, 1, 0.0, 0.0, GridBagConstraints.FIRST_LINE_START,
               GridBagConstraints.HORIZONTAL, new Insets(0, 8, 0, 0), 0, 0);
    p.add(lPosX, c);
    c = setGBC(c, 0, 1, 1, 1, 0.0, 0.0, GridBagConstraints.FIRST_LINE_START,
               GridBagConstraints.NONE, new Insets(4, 0, 0, 0), 0, 0);
    p.add(lPosYLabel, c);
    c = setGBC(c, 1, 1, 1, 1, 0.0, 0.0, GridBagConstraints.FIRST_LINE_START,
               GridBagConstraints.HORIZONTAL, new Insets(4, 8, 0, 0), 0, 0);
    p.add(lPosY, c);
    c = setGBC(c, 0, 2, 2, 1, 1.0, 1.0, GridBagConstraints.FIRST_LINE_START,
               GridBagConstraints.BOTH, new Insets(4, 0, 0, 0), 0, 0);
    p.add(taInfo, c);

    JPanel pInfoBox = new JPanel(new GridBagLayout());
    pInfoBox.setBorder(BorderFactory.createTitledBorder("Information: "));
    c = setGBC(c, 0, 0, 1, 1, 1.0, 0.0, GridBagConstraints.FIRST_LINE_START,
               GridBagConstraints.BOTH, new Insets(0, 4, 0, 4), 0, 0);
    pInfoBox.add(p, c);

    // Assembling right side bar
    JPanel pSideBar = new JPanel(new GridBagLayout());
    c = setGBC(c, 0, 0, 1, 1, 0.0, 0.0, GridBagConstraints.FIRST_LINE_START,
               GridBagConstraints.HORIZONTAL, new Insets(4, 4, 0, 4), 0, 0);
    pSideBar.add(pVisualState, c);
    c = setGBC(c, 0, 1, 1, 1, 0.0, 0.0, GridBagConstraints.FIRST_LINE_START,
               GridBagConstraints.HORIZONTAL, new Insets(8, 4, 0, 4), 0, 0);
    pSideBar.add(pLayers, c);
    c = setGBC(c, 0, 2, 1, 1, 0.0, 0.0, GridBagConstraints.FIRST_LINE_START,
               GridBagConstraints.HORIZONTAL, new Insets(8, 4, 0, 4), 0, 0);
    pSideBar.add(pInfoBox, c);
    c = setGBC(c, 0, 3, 1, 1, 0.0, 1.0, GridBagConstraints.FIRST_LINE_START,
               GridBagConstraints.BOTH, new Insets(4, 0, 0, 0), 0, 0);
    pSideBar.add(new JPanel(), c);

    // Creating toolbar
    Dimension dimSeparator = new Dimension(24, 40);
    toolBar = new JToolBar("Area Viewer Controls", SwingConstants.HORIZONTAL);
    toolBar.setRollover(true);
    toolBar.setFloatable(false);
    tbView = new JToggleButton(Icons.getIcon("icn_viewMode.png"), true);
    tbView.setToolTipText("Enter view mode");
    tbView.addActionListener(this);
    tbView.setEnabled(false);
//    toolBar.add(tbView);
    tbEdit = new JToggleButton(Icons.getIcon("icn_editMode.png"), false);
    tbEdit.setToolTipText("Enter edit mode");
    tbEdit.addActionListener(this);
    tbEdit.setEnabled(false);
//    toolBar.add(tbEdit);

//    toolBar.addSeparator(dimSeparator);

    JToggleButton tb;
    tb = new JToggleButton(Icons.getIcon("icn_addActor.png"), false);
    tb.setToolTipText("Add a new actor to the map");
    tb.addActionListener(this);
    tb.setEnabled(false);
//    toolBar.add(tb);
    tbAddLayerItem[LayerManager.getLayerTypeIndex(LayerType.Actor)] = tb;
    tb = new JToggleButton(Icons.getIcon("icn_addRegion.png"), false);
    tb.setToolTipText("Add a new region to the map");
    tb.addActionListener(this);
    tb.setEnabled(false);
//    toolBar.add(tb);
    tbAddLayerItem[LayerManager.getLayerTypeIndex(LayerType.Region)] = tb;
    tb = new JToggleButton(Icons.getIcon("icn_addEntrance.png"), false);
    tb.setToolTipText("Add a new entrance to the map");
    tb.addActionListener(this);
    tb.setEnabled(false);
//    toolBar.add(tb);
    tbAddLayerItem[LayerManager.getLayerTypeIndex(LayerType.Entrance)] = tb;
    tb = new JToggleButton(Icons.getIcon("icn_addContainer.png"), false);
    tb.setToolTipText("Add a new container to the map");
    tb.addActionListener(this);
    tb.setEnabled(false);
//    toolBar.add(tb);
    tbAddLayerItem[LayerManager.getLayerTypeIndex(LayerType.Container)] = tb;
    tb = new JToggleButton(Icons.getIcon("icn_addAmbient.png"), false);
    tb.setToolTipText("Add a new global ambient sound to the map");
    tb.addActionListener(this);
    tb.setEnabled(false);
//    toolBar.add(tb);
    tbAddLayerItem[LayerManager.getLayerTypeIndex(LayerType.Ambient)] = tb;
    tb = new JToggleButton(Icons.getIcon("icn_addDoor.png"), false);
    tb.setToolTipText("Add a new door to the map");
    tb.addActionListener(this);
    tb.setEnabled(false);
//    toolBar.add(tb);
    tbAddLayerItem[LayerManager.getLayerTypeIndex(LayerType.Door)] = tb;
    tb = new JToggleButton(Icons.getIcon("icn_addAnim.png"), false);
    tb.setToolTipText("Add a new background animation to the map");
    tb.addActionListener(this);
    tb.setEnabled(false);
//    toolBar.add(tb);
    tbAddLayerItem[LayerManager.getLayerTypeIndex(LayerType.Animation)] = tb;
    tb = new JToggleButton(Icons.getIcon("icn_addAutomap.png"), false);
    tb.setToolTipText("Add a new automap note to the map");
    tb.addActionListener(this);
    tb.setEnabled(false);
//    toolBar.add(tb);
    tbAddLayerItem[LayerManager.getLayerTypeIndex(LayerType.Automap)] = tb;
    tb = new JToggleButton(Icons.getIcon("icn_addSpawnPoint.png"), false);
    tb.setToolTipText("Add a new spawn point to the map");
    tb.addActionListener(this);
    tb.setEnabled(false);
//    toolBar.add(tb);
    tbAddLayerItem[LayerManager.getLayerTypeIndex(LayerType.SpawnPoint)] = tb;
    tb = new JToggleButton(Icons.getIcon("icn_addProTrap.png"), false);
    tb.setToolTipText("Add a new projectile trap to the map");
    tb.addActionListener(this);
    tb.setEnabled(false);
//    toolBar.add(tb);
    tbAddLayerItem[LayerManager.getLayerTypeIndex(LayerType.ProTrap)] = tb;
    tb = new JToggleButton(Icons.getIcon("icn_addDoorPoly.png"), false);
    tb.setToolTipText("Add a new door polygon to the map");
    tb.addActionListener(this);
    tb.setEnabled(false);
//    toolBar.add(tb);
    tbAddLayerItem[LayerManager.getLayerTypeIndex(LayerType.DoorPoly)] = tb;
    tb = new JToggleButton(Icons.getIcon("icn_addWallPoly.png"), false);
    tb.setToolTipText("Add a new wall polygon to the map");
    tb.addActionListener(this);
    tb.setEnabled(false);
//    toolBar.add(tb);
    tbAddLayerItem[LayerManager.getLayerTypeIndex(LayerType.WallPoly)] = tb;

//    toolBar.addSeparator(dimSeparator);

    tbAre = new JButton(Icons.getIcon("icn_mapAre.png"));
    tbAre.setToolTipText(String.format("Edit ARE structure (%1$s)", map.getAre().getName()));
    tbAre.addActionListener(this);
    toolBar.add(tbAre);
    tbWed = new JButton(Icons.getIcon("icn_mapWed.png"));
    tbWed.addActionListener(this);
    toolBar.add(tbWed);
    tbSongs = new JButton(Icons.getIcon("icn_songs.png"));
    tbSongs.setToolTipText("Edit song entries");
    tbSongs.addActionListener(this);
    toolBar.add(tbSongs);
    tbRest = new JButton(Icons.getIcon("icn_rest.png"));
    tbRest.setToolTipText("Edit rest encounters");
    tbRest.addActionListener(this);
    toolBar.add(tbRest);

    toolBar.addSeparator(dimSeparator);

    tbSettings = new JButton(Icons.getIcon("icn_settings.png"));
    tbSettings.setToolTipText("Area viewer settings");
    tbSettings.addActionListener(this);
    toolBar.add(tbSettings);
    tbRefresh = new JButton(Icons.getIcon("icn_refresh.png"));
    tbRefresh.setToolTipText("Update map");
    tbRefresh.addActionListener(this);
    toolBar.add(tbRefresh);

    updateToolBarButtons();

    // Putting all together
    JPanel pMain = new JPanel(new GridBagLayout());
    c = setGBC(c, 0, 0, 1, 1, 1.0, 1.0, GridBagConstraints.FIRST_LINE_START,
               GridBagConstraints.BOTH, new Insets(0, 0, 0, 0), 0, 0);
    pMain.add(pView, c);
    c = setGBC(c, 1, 0, 1, 1, 0.0, 1.0, GridBagConstraints.FIRST_LINE_START,
               GridBagConstraints.VERTICAL, new Insets(0, 0, 0, 0), 0, 0);
    pMain.add(pSideBar, c);

    // setting frame rate for overlay animations to 5 fps (in-game frame rate: 7.5 fps)
    timerOverlays = new Timer(1000/5, this);

    advanceProgressMonitor("Initializing map...");
    Container pane = getContentPane();
    pane.setLayout(new BorderLayout());
    pane.add(pMain, BorderLayout.CENTER);
    pane.add(toolBar, BorderLayout.NORTH);
    pack();

    // setting window size and state
    setSize(NearInfinity.getInstance().getSize());
    Center.center(this, NearInfinity.getInstance().getBounds());
    setExtendedState(NearInfinity.getInstance().getExtendedState());

    try {
      initGuiSettings();
    } catch (OutOfMemoryError e) {
      JOptionPane.showMessageDialog(this, "Not enough memory to load area!", "Error", JOptionPane.ERROR_MESSAGE);
      throw e;
    }
    rcCanvas.requestFocusInWindow();    // put focus on a safe component
    advanceProgressMonitor("Ready!");

    updateWindowTitle();
    setVisible(true);
  }


  // Sets the state of all GUI components and their associated actions
  private void initGuiSettings()
  {
    Settings.loadSettings(false);

    // initializing visual state of the map
    setHour(Settings.TimeOfDay);

    // initializing time schedules for layer items
    cbEnableSchedules.setSelected(Settings.EnableSchedules);

    // initializing closed state of doors
    cbDrawClosed.setSelected(Settings.DrawClosed);
    cbDrawClosed.setEnabled(rcCanvas.hasDoors());
    if (rcCanvas.hasDoors()) {
      setDoorState(Settings.DrawClosed);
    }

    // initializing grid
    cbDrawGrid.setSelected(Settings.DrawGrid);
    setTileGridEnabled(Settings.DrawGrid);

    // initializing overlays
    cbDrawOverlays.setSelected(Settings.DrawOverlays);
    cbDrawOverlays.setEnabled(rcCanvas.hasOverlays());
    cbAnimateOverlays.setEnabled(rcCanvas.hasOverlays());
    if (rcCanvas.hasOverlays()) {
      setOverlaysEnabled(Settings.DrawOverlays);
      setOverlaysAnimated(cbAnimateOverlays.isSelected());
    }

    // initializing zoom level
    cbZoomLevel.setSelectedIndex(Settings.ZoomLevel);

    // initializing layers
    layerManager = new LayerManager(getCurrentAre(), getCurrentWed(), this);
    layerManager.setDoorState(Settings.DrawClosed ? ViewerConstants.DOOR_CLOSED : ViewerConstants.DOOR_OPEN);
    layerManager.setScheduleEnabled(Settings.EnableSchedules);
    layerManager.setSchedule(LayerManager.toSchedule(getHour()));
    addLayerItems();
    updateScheduledItems();
    for (int i = 0; i < LayerManager.getLayerTypeCount(); i++) {
      LayerType layer = LayerManager.getLayerType(i);
      int bit = 1 << i;
      boolean isChecked = (Settings.LayerFlags & bit) != 0;
      int count = layerManager.getLayerObjectCount(layer);
      if (count > 0) {
        cbLayers[i].setToolTipText(layerManager.getLayerAvailability(layer));
      }
      cbLayers[i].setEnabled(count > 0);
      cbLayers[i].setSelected(isChecked);
      updateLayerItems(Settings.layerToStacking(layer));
      showLayer(LayerManager.getLayerType(i), cbLayers[i].isSelected());
    }

    // Setting up ambient sound ranges
    LayerAmbient layerAmbient = (LayerAmbient)layerManager.getLayer(ViewerConstants.LayerType.Ambient);
    cbLayerAmbientRange.setToolTipText(layerAmbient.getAvailability(ViewerConstants.AMBIENT_TYPE_LOCAL));
    cbLayerAmbientRange.setSelected(Settings.ShowAmbientRanges);
    updateAmbientRange();

    // initializing background animation display
    // Disabling animated frames for performance and safety reasons
    if (Settings.ShowRealAnimations == ViewerConstants.ANIM_SHOW_ANIMATED) {
      Settings.ShowRealAnimations = ViewerConstants.ANIM_SHOW_STILL;
    }
    ((LayerAnimation)layerManager.getLayer(LayerType.Animation)).setRealAnimationFrameState(Settings.ShowFrame);
    cbLayerRealAnimation[0].setSelected(Settings.ShowRealAnimations == ViewerConstants.ANIM_SHOW_STILL);
    cbLayerRealAnimation[1].setSelected(false);
    updateRealAnimation();

    updateWindowTitle();
    applySettings();
  }


  // Updates the window title
  private void updateWindowTitle()
  {
    int zoom = (int)(getZoomFactor()*100.0);

    String dayNight;
    switch (getVisualState()) {
      case ViewerConstants.LIGHTING_TWILIGHT:
        dayNight = "twilight";
        break;
      case ViewerConstants.LIGHTING_NIGHT:
        dayNight = "night";
        break;
      default:
        dayNight = "day";
    }

    String scheduleState = Settings.EnableSchedules ? "enabled" : "disabled";

    String doorState = isDoorStateClosed() ? "closed" : "open";

    String overlayState;
    if (isOverlaysEnabled() && !isOverlaysAnimated()) {
      overlayState = "enabled";
    } else if (isOverlaysEnabled() && isOverlaysAnimated()) {
      overlayState = "animated";
    } else {
      overlayState = "disabled";
    }

    String gridState = isTileGridEnabled() ? "enabled" : "disabled";

    setTitle(String.format("%1$s  (Time: %2$02d:00 (%3$s), Schedules: %4$s, Doors: %5$s, Overlays: %6$s, Grid: %7$s, Zoom: %8$d%%)",
                           windowTitle, getHour(), dayNight, scheduleState, doorState, overlayState, gridState, zoom));
  }

  // Returns the general day time (day/twilight/night)
  private static int getDayTime()
  {
    return ViewerConstants.getDayTime(Settings.TimeOfDay);
  }

  // Returns the currently selected day time in hours
  private static int getHour()
  {
    return Settings.TimeOfDay;
  }

  // Sets day time to a specific hour (0..23).
  private void setHour(int hour)
  {
    while (hour < 0) { hour += 24; }
    hour %= 24;
    Settings.TimeOfDay = hour;
    setVisualState(getHour());
    if (layerManager != null) {
      layerManager.setSchedule(LayerManager.toSchedule(getHour()));
    }
    if (pDayTime != null) {
      pDayTime.setHour(Settings.TimeOfDay);
    }
    updateScheduledItems();
  }

  // Returns the currently selected ARE resource
  private AreResource getCurrentAre()
  {
    if (map != null) {
      return map.getAre();
    } else {
      return null;
    }
  }

  // Returns the currently selected WED resource (day/night)
  private WedResource getCurrentWed()
  {
    if (map != null) {
      return map.getWed(getCurrentWedIndex());
    }
    return null;
  }

  // Returns the currently selected WED resource (day/night)
  private int getCurrentWedIndex()
  {
    if (map != null) {
      return getDayTime() == ViewerConstants.LIGHTING_NIGHT ? Map.MAP_NIGHT : Map.MAP_DAY;
    } else {
      return Map.MAP_DAY;
    }
  }


  // Returns the currently selected visual state (day/twilight/night)
  private int getVisualState()
  {
    return getDayTime();
  }

  // Set the lighting condition of the current map (day/twilight/night) and real background animations
  private synchronized void setVisualState(int hour)
  {
    while (hour < 0) { hour += 24; }
    hour %= 24;
    int index = ViewerConstants.getDayTime(hour);
    if (!map.hasDayNight()) {
      index = ViewerConstants.LIGHTING_DAY;
    }
    if (index >= ViewerConstants.LIGHTING_DAY &&
        index <= ViewerConstants.LIGHTING_NIGHT) {
      switch (index) {
        case ViewerConstants.LIGHTING_DAY:
          if (!isProgressMonitorActive() && map.getWed(Map.MAP_DAY) != rcCanvas.getWed()) {
            initProgressMonitor(this, "Loading tileset...", null, 1, 0, 0);
          }
          if (!rcCanvas.isMapLoaded() || rcCanvas.getWed() != map.getWed(Map.MAP_DAY)) {
            rcCanvas.loadMap(map.getWed(Map.MAP_DAY));
            reloadWedLayers(true);
          }
          rcCanvas.setLighting(index);
          break;
        case ViewerConstants.LIGHTING_TWILIGHT:
          if (!isProgressMonitorActive() && map.getWed(Map.MAP_DAY) != rcCanvas.getWed()) {
            initProgressMonitor(this, "Loading tileset...", null, 1, 0, 0);
          }
          if (!rcCanvas.isMapLoaded() || rcCanvas.getWed() != map.getWed(Map.MAP_DAY)) {
            rcCanvas.loadMap(map.getWed(Map.MAP_DAY));
            reloadWedLayers(true);
          }
          rcCanvas.setLighting(index);
          break;
        case ViewerConstants.LIGHTING_NIGHT:
          if (!isProgressMonitorActive() && map.getWed(Map.MAP_NIGHT) != rcCanvas.getWed()) {
            initProgressMonitor(this, "Loading tileset...", null, 1, 0, 0);
          }
          if (!rcCanvas.isMapLoaded() || map.hasExtendedNight()) {
            if (rcCanvas.getWed() != map.getWed(Map.MAP_NIGHT)) {
              rcCanvas.loadMap(map.getWed(Map.MAP_NIGHT));
              reloadWedLayers(true);
            }
          }
          if (!map.hasExtendedNight()) {
            rcCanvas.setLighting(index);
          }
          break;
      }
      // updating current visual state
      if (hour != getHour()) {
        Settings.TimeOfDay = hour;
      }

      updateToolBarButtons();
      updateRealAnimationsLighting(getDayTime());
      updateScheduledItems();
      updateWindowTitle();
    }
  }


  // Sets visibility state of scheduled layer items depending on current day time
  private void updateScheduledItems()
  {
    if (layerManager != null) {
      for (int i = 0; i < LayerManager.getLayerTypeCount(); i++) {
        LayerType layer = LayerManager.getLayerType(i);
        layerManager.setLayerVisible(layer, isLayerEnabled(layer));
      }
    }
  }


  // Applies the specified lighting condition to real animation items
  private void updateRealAnimationsLighting(int visualState)
  {
    if (layerManager != null) {
      List<LayerObject> list = layerManager.getLayerObjects(LayerType.Animation);
      if (list != null) {
        for (int i = 0; i < list.size(); i++) {
          LayerObjectAnimation obj = (LayerObjectAnimation)list.get(i);
          obj.setLighting(visualState);
        }
      }
    }
  }


  // Returns whether map dragging is enabled; updates current and previous mouse positions
  private boolean isMapDragging(Point mousePos)
  {
    if (bMapDragging && mousePos != null && !mapDraggingPos.equals(mousePos)) {
      mapDraggingPos.x = mousePos.x;
      mapDraggingPos.y = mousePos.y;
    }
    return bMapDragging;
  }

  // Enables/disables map dragging mode (set mouse cursor, global state and current mouse position)
  private void setMapDraggingEnabled(boolean enable, Point mousePos)
  {
    if (bMapDragging != enable) {
      bMapDragging = enable;
      setCursor(Cursor.getPredefinedCursor(bMapDragging ? Cursor.MOVE_CURSOR : Cursor.DEFAULT_CURSOR));
      if (bMapDragging && mousePos != null) {
        mapDraggingPosStart.x = mapDraggingPos.x = mousePos.x;
        mapDraggingPosStart.y = mapDraggingPos.y = mousePos.y;
        mapDraggingScrollStart.x = spCanvas.getHorizontalScrollBar().getModel().getValue();
        mapDraggingScrollStart.y = spCanvas.getVerticalScrollBar().getModel().getValue();
      }
    }
  }

  // Returns the current or previous mouse position
  private Point getMapDraggingDistance()
  {
    Point pDelta = new Point();
    if (bMapDragging) {
      pDelta.x = mapDraggingPosStart.x - mapDraggingPos.x;
      pDelta.y = mapDraggingPosStart.y - mapDraggingPos.y;
    }
    return pDelta;
  }

  // Updates the map portion displayed in the viewport
  private void moveMapViewport()
  {
    if (!mapDraggingPosStart.equals(mapDraggingPos)) {
      Point distance = getMapDraggingDistance();
      JViewport vp = spCanvas.getViewport();
      Point curPos = vp.getViewPosition();
      Dimension curDim = vp.getExtentSize();
      Dimension maxDim = new Dimension(spCanvas.getHorizontalScrollBar().getMaximum(),
                                       spCanvas.getVerticalScrollBar().getMaximum());
      if (curDim.width < maxDim.width) {
        curPos.x = mapDraggingScrollStart.x + distance.x;
        if (curPos.x < 0) curPos.x = 0;
        if (curPos.x + curDim.width > maxDim.width) curPos.x = maxDim.width - curDim.width;
      }
      if (curDim.height < maxDim.height) {
        curPos.y = mapDraggingScrollStart.y + distance.y;
        if (curPos.y < 0) curPos.y = 0;
        if (curPos.y + curDim.height > maxDim.height) curPos.y = maxDim.height - curDim.height;
      }
      vp.setViewPosition(curPos);
    }
  }


  // Returns whether closed door state is active
  private boolean isDoorStateClosed()
  {
    return Settings.DrawClosed;
  }

  // Draw opened/closed state of doors (affects map tiles, door layer and door poly layer)
  private void setDoorState(boolean closed)
  {
    Settings.DrawClosed = closed;
    setDoorStateMap(closed);
    setDoorStateLayers(closed);
    updateWindowTitle();
  }

  // Called by setDoorState(): sets door state map tiles
  private void setDoorStateMap(boolean closed)
  {
    if (rcCanvas != null) {
      rcCanvas.setDoorsClosed(Settings.DrawClosed);
    }
  }

  // Called by setDoorState(): sets door state in door layer and door poly layer
  private void setDoorStateLayers(boolean closed)
  {
    if (layerManager != null) {
      layerManager.setDoorState(Settings.DrawClosed ? ViewerConstants.DOOR_CLOSED : ViewerConstants.DOOR_OPEN);
    }
  }


  // Returns whether tile grid on map has been enabled
  private boolean isTileGridEnabled()
  {
    return Settings.DrawGrid;
  }

  // Enable/disable tile grid on map
  private void setTileGridEnabled(boolean enable)
  {
    Settings.DrawGrid = enable;
    if (rcCanvas != null) {
      rcCanvas.setGridEnabled(Settings.DrawGrid);
    }
    updateWindowTitle();
  }


  // Returns whether overlays are enabled (considers both internal overlay flag and whether the map contains overlays)
  private boolean isOverlaysEnabled()
  {
    return Settings.DrawOverlays;
  }

  // Enable/disable overlays
  private void setOverlaysEnabled(boolean enable)
  {
    Settings.DrawOverlays = enable;
    if (rcCanvas != null) {
      rcCanvas.setOverlaysEnabled(Settings.DrawOverlays);
    }
    updateWindowTitle();
  }


  // Returns whether overlays are animated
  private boolean isOverlaysAnimated()
  {
    if (timerOverlays != null) {
      return (isOverlaysEnabled() && timerOverlays.isRunning());
    } else {
      return false;
    }
  }

  // Activate/deactivate overlay animations
  private void setOverlaysAnimated(boolean animate)
  {
    if (timerOverlays != null) {
      if (animate && !timerOverlays.isRunning()) {
        timerOverlays.start();
      } else if (!animate && timerOverlays.isRunning()) {
        timerOverlays.stop();
      }
      updateWindowTitle();
    }
  }

  // Advances animated overlays by one frame
  private synchronized void advanceOverlayAnimation()
  {
    if (rcCanvas != null) {
      rcCanvas.advanceTileFrame();
    }
  }


  // Returns the currently used zoom factor of the canvas map
  private double getZoomFactor()
  {
    if (rcCanvas != null) {
      return rcCanvas.getZoomFactor();
    } else {
      return Settings.ItemZoomFactor[Settings.ZoomLevel];
    }
  }

  // Sets a new zoom level to the map and associated structures
  private void setZoomLevel(int zoomIndex)
  {
    zoomIndex = Math.min(Math.max(zoomIndex, 0), Settings.ItemZoomFactor.length - 1);
    updateViewpointCenter();
    double zoom = 1.0;
    if (zoomIndex == Settings.ZoomFactorIndexAuto) {
      // removing scrollbars (not needed in this mode)
      boolean needValidate = false;
      if (spCanvas.getHorizontalScrollBarPolicy() != ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER) {
        spCanvas.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        needValidate = true;
      }
      if (spCanvas.getVerticalScrollBarPolicy() != ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER) {
        spCanvas.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER);
        needValidate = true;
      }
      if (needValidate) {
        spCanvas.validate();    // required for determining the correct viewport size
      }
      // determining zoom factor by preserving correct aspect ratio
      Dimension viewDim = new Dimension(spCanvas.getViewport().getExtentSize());
      Dimension mapDim = new Dimension(rcCanvas.getMapWidth(false), rcCanvas.getMapHeight(false));
      double zoomX = (double)viewDim.width / (double)mapDim.width;
      double zoomY = (double)viewDim.height / (double)mapDim.height;
      zoom = zoomX;
      if ((int)(zoomX*mapDim.height) > viewDim.height) {
        zoom = zoomY;
      }
    } else {
      // (re-)activating scrollbars
      if (spCanvas.getHorizontalScrollBarPolicy() != ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED) {
        spCanvas.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
      }
      if (spCanvas.getVerticalScrollBarPolicy() != ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED) {
        spCanvas.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
      }
      zoom = Settings.ItemZoomFactor[zoomIndex];
    }
    if (rcCanvas != null) {
      rcCanvas.setZoomFactor(zoom);
    }
    Settings.ZoomLevel = zoomIndex;
    updateWindowTitle();
  }

  // Returns whether auto-fit has been selected
  private boolean isAutoZoom()
  {
    return (Settings.ZoomLevel == Settings.ZoomFactorIndexAuto);
  }


  // Updates the map coordinate at the center of the current viewport
  private void updateViewpointCenter()
  {
    if (vpCenterExtent == null) {
      vpCenterExtent = new Rectangle();
    }
    Dimension mapDim = new Dimension(rcCanvas.getMapWidth(true), rcCanvas.getMapHeight(true));
    JViewport vp = spCanvas.getViewport();
    Rectangle view = vp.getViewRect();
    vpCenterExtent.x = view.x + (view.width / 2);
    vpCenterExtent.y = view.y + (view.height / 2);
    if (view.width > mapDim.width) {
      vpCenterExtent.x = mapDim.width / 2;
    }
    if (view.height > mapDim.height) {
      vpCenterExtent.y = mapDim.height / 2;
    }
    // canvas coordinate -> map coordinate
    vpCenterExtent.x = (int)((double)vpCenterExtent.x / getZoomFactor());
    vpCenterExtent.y = (int)((double)vpCenterExtent.y / getZoomFactor());

    vpCenterExtent.width = vp.getViewSize().width;
    vpCenterExtent.height = vp.getViewSize().height;
  }

  // Attempts to re-center the last known center coordinate in the current viewport
  private void setViewpointCenter()
  {
    if (vpCenterExtent != null) {
      if (!vpCenterExtent.getSize().equals(spCanvas.getViewport().getViewSize())) {
        Dimension mapDim = new Dimension(rcCanvas.getMapWidth(true), rcCanvas.getMapHeight(true));

        // map coordinate -> canvas coordinate
        vpCenterExtent.x = (int)((double)vpCenterExtent.x * getZoomFactor());
        vpCenterExtent.y = (int)((double)vpCenterExtent.y * getZoomFactor());

        JViewport vp = spCanvas.getViewport();
        Rectangle view = vp.getViewRect();
        Point newViewPos = new Point(vpCenterExtent.x - (view.width / 2), vpCenterExtent.y - (view.height / 2));
        if (newViewPos.x < 0) {
          newViewPos.x = 0;
        } else if (newViewPos.x + view.width > mapDim.width) {
          newViewPos.x = mapDim.width - view.width;
        }
        if (newViewPos.y < 0) {
          newViewPos.y = 0;
        } else if (newViewPos.y + view.height > mapDim.height) {
          newViewPos.y = mapDim.height - view.height;
        }

        vpCenterExtent = null;
        vp.setViewPosition(newViewPos);
      }
    }
  }


  // Updates the map coordinates pointed to by the current cursor position
  private void showMapCoordinates(Point coords)
  {
    if (coords != null) {
      // Converting canvas coordinates -> map coordinates
      coords.x = (int)((double)coords.x / getZoomFactor());
      coords.y = (int)((double)coords.y / getZoomFactor());
      if (coords.x != mapCoordinates.x) {
        mapCoordinates.x = coords.x;
        lPosX.setText(Integer.toString(mapCoordinates.x));
      }
      if (coords.y != mapCoordinates.y) {
        mapCoordinates.y = coords.y;
        lPosY.setText(Integer.toString(mapCoordinates.y));
      }
    }
  }

  // Shows a description in the info box
  private void setInfoText(String text)
  {
    if (taInfo != null) {
      if (text != null) {
        taInfo.setText(text);
      } else {
        taInfo.setText("");
      }
    }
  }


  // Creates and displays a popup menu containing the items located at the specified location
  private boolean updateItemPopup(Point canvasCoords)
  {
    final int MaxLen = 32;    // max. length of a menuitem text

    if (layerManager != null) {
      // preparing menu items
      List<JMenuItem> menuItems = new ArrayList<JMenuItem>();
      Point itemLocation = new Point();
      pmItems.removeAll();

      // for each active layer...
      for (int i = 0; i < Settings.ListLayerOrder.size(); i++) {
        LayerStackingType stacking = Settings.ListLayerOrder.get(i);
        LayerType layer = Settings.stackingToLayer(stacking);
        if (isLayerEnabled(stacking)) {
          List<LayerObject> itemList = layerManager.getLayerObjects(layer);
          if (itemList != null && !itemList.isEmpty()) {
            // for each layer object...
            for (int j = 0; j < itemList.size(); j++) {
              AbstractLayerItem[] items = itemList.get(j).getLayerItems();
              // for each layer item...
              for (int k = 0; k < items.length; k++) {
                // special case: Ambient/Ambient range (avoiding duplicates)
                if (stacking == LayerStackingType.Ambient &&
                    cbLayerAmbientRange.isSelected() &&
                    ((LayerObjectAmbient)itemList.get(j)).isLocal()) {
                  // skipped: will be handled in AmbientRange layer
                  break;
                }
                if (stacking == LayerStackingType.AmbientRange) {
                  if (((LayerObjectAmbient)itemList.get(j)).isLocal() &&
                      k == ViewerConstants.AMBIENT_ITEM_ICON) {
                    // considering ranged item only
                    continue;
                  } else if (!((LayerObjectAmbient)itemList.get(j)).isLocal()) {
                    // global sounds don't have ambient ranges
                    break;
                  }
                }
                itemLocation.x = canvasCoords.x - items[k].getX();
                itemLocation.y = canvasCoords.y - items[k].getY();
                if (items[k].isVisible() && items[k].contains(itemLocation)) {
                  // creating a new menu item
                  StringBuilder sb = new StringBuilder();
                  if (items[k].getName() != null && !items[k].getName().isEmpty()) {
                    sb.append(items[k].getName());
                  } else {
                    sb.append("Item");
                  }
                  sb.append(": ");
                  int lenPrefix = sb.length();
                  int lenMsg = items[k].getMessage().length();
                  if (lenPrefix + lenMsg > MaxLen) {
                    sb.append(items[k].getMessage().substring(0, MaxLen - lenPrefix));
                    sb.append("...");
                  } else {
                    sb.append(items[k].getMessage());
                  }
                  LayerMenuItem lmi = new LayerMenuItem(sb.toString(), items[k]);
                  if (lenPrefix + lenMsg > MaxLen) {
                    lmi.setToolTipText(items[k].getMessage());
                  }
                  lmi.addActionListener(this);
                  menuItems.add(lmi);
                }
              }
            }
          }
        }
      }

      // updating context menu with the prepared item list
      if (!menuItems.isEmpty()) {
        for (int i = 0; i < menuItems.size(); i++) {
          pmItems.add(menuItems.get(i));
        }
      }
      return !menuItems.isEmpty();
    }
    return false;
  }

  // Shows a popup menu containing layer items located at the current position if available
  private void showItemPopup(MouseEvent event)
  {
    if (event != null && event.isPopupTrigger()) {
      Component parent = null;
      Point location = null;
      if (event.getSource() instanceof AbstractLayerItem) {
        parent = (AbstractLayerItem)event.getSource();
        location = parent.getLocation();
        location.translate(event.getX(), event.getY());
      } else if (event.getSource() == rcCanvas) {
        parent = rcCanvas;
        location = event.getPoint();
      }

      if (parent != null && location != null) {
        if (updateItemPopup(location)) {
          pmItems.show(parent, event.getX(), event.getY());
        }
      }
    }
  }


  // Updates all available layer items
  private void reloadLayers()
  {
    reloadAreLayers(false);
    reloadWedLayers(false);
    orderLayerItems();
  }

  // Updates ARE-related layer items
  private void reloadAreLayers(boolean order)
  {
    if (layerManager != null) {
//      layerManager.setAreResource(getCurrentAre());
      for (int i = 0; i < LayerManager.getLayerTypeCount(); i++) {
        LayerType layer = LayerManager.getLayerType(i);
        LayerStackingType layer2 = Settings.layerToStacking(layer);
        if (layer != LayerType.DoorPoly && layer != LayerType.WallPoly) {
          removeLayerItems(layer2);
          if (layer == LayerType.Ambient) {
            removeLayerItems(LayerStackingType.AmbientRange);
          }
          layerManager.reload(layer);
          updateLayerItems(layer2);
          addLayerItems(layer2);
          if (layer == LayerType.Ambient) {
            updateLayerItems(LayerStackingType.AmbientRange);
            addLayerItems(LayerStackingType.AmbientRange);
          }
          showLayer(layer, cbLayers[i].isSelected());
        }
      }
    }
    updateAmbientRange();
    updateRealAnimation();
    if (order) {
      orderLayerItems();
    }
    rcCanvas.repaint();
  }

  // Updates WED-related layer items
  private void reloadWedLayers(boolean order)
  {
    if (layerManager != null) {
      removeLayerItems(LayerStackingType.DoorPoly);
      removeLayerItems(LayerStackingType.WallPoly);
      layerManager.setWedResource(getCurrentWed());
      updateLayerItems(LayerStackingType.DoorPoly);
      addLayerItems(LayerStackingType.DoorPoly);
      updateLayerItems(LayerStackingType.WallPoly);
      addLayerItems(LayerStackingType.WallPoly);
      showLayer(LayerType.DoorPoly, cbLayers[LayerManager.getLayerTypeIndex(LayerType.DoorPoly)].isSelected());
      showLayer(LayerType.WallPoly, cbLayers[LayerManager.getLayerTypeIndex(LayerType.WallPoly)].isSelected());
    }
    if (order) {
      orderLayerItems();
    }
    rcCanvas.repaint();
  }


  // Returns the identifier of the specified layer checkbox, or null on error
  private LayerType getLayerType(JCheckBox cb)
  {
    if (cb != null) {
      for (int i = 0; i < cbLayers.length; i++) {
        if (cb == cbLayers[i]) {
          return LayerManager.getLayerType(i);
        }
      }
    }
    return null;
  }

  // Returns whether the specified layer is visible (by layer)
  private boolean isLayerEnabled(LayerType layer)
  {
    if (layer != null) {
      return ((Settings.LayerFlags & (1 << LayerManager.getLayerTypeIndex(layer))) != 0);
    } else {
      return false;
    }
  }

  // Returns whether the specified layer is visible (by stacked layer)
  private boolean isLayerEnabled(LayerStackingType layer)
  {
    return isLayerEnabled(Settings.stackingToLayer(layer));
  }

  // Returns whether the specified layer is visible (by layer index)
  private boolean isLayerEnabled(int layerIndex)
  {
    return isLayerEnabled(LayerManager.getLayerType(layerIndex));
  }

  // Returns whether the specified layer is visible (by layer control)
  private boolean isLayerEnabled(JCheckBox cb)
  {
    if (cb != null) {
      for (int i = 0; i < cbLayers.length; i++) {
        if (cbLayers[i] == cb) {
          return cbLayers[i].isSelected();
        }
      }
    }
    return false;
  }


  // Opens a viewable instance associated with the specified layer item
  private void showTable(AbstractLayerItem item)
  {
    if (item != null) {
      if (item.getViewable() instanceof AbstractStruct) {
        Window wnd = getViewerWindow((AbstractStruct)item.getViewable());
        ((AbstractStruct)item.getViewable()).selectEditTab();
        wnd.setVisible(true);
        wnd.toFront();
      } else {
        item.showViewable();
      }
    }
  }

  // Attempts to find the Window instance containing the viewer of the specified AbstractStruct object
  // If it cannot find one, it creates and returns a new one.
  // If all fails, it returns the NearInfinity instance.
  private Window getViewerWindow(AbstractStruct as)
  {
    if (as != null) {
      if (as.getViewer() != null && as.getViewer().getParent() != null) {
        // Determining whether the structure is associated with any open NearInfinity window
        StructViewer sv = as.getViewer();
        Component[] list = sv.getParent().getComponents();
        if (list != null) {
          for (int i = 0; i < list.length; i++) {
            if (list[i] == sv) {
              Component c = sv.getParent();
              while (c != null) {
                if (c instanceof Window) {
                  // Window found, returning
                  return (Window)c;
                }
                c = c.getParent();
              }
            }
          }
        }
      }
      // Window not found, creating and returning a new one
      return new ViewFrame(NearInfinity.getInstance(), as);
    }
    // Last resort: returning NearInfinity instance
    return NearInfinity.getInstance();
  }

  // Applying time schedule settings to layer items
  private void updateTimeSchedules()
  {
    layerManager.setScheduleEnabled(Settings.EnableSchedules);
    updateWindowTitle();
  }

  // Updates the state of the ambient sound range checkbox and associated functionality
  private void updateAmbientRange()
  {
    if (layerManager != null) {
      LayerAmbient layer = (LayerAmbient)layerManager.getLayer(LayerType.Ambient);
      if (layer != null) {
        JCheckBox cb = cbLayers[LayerManager.getLayerTypeIndex(LayerType.Ambient)];
        cbLayerAmbientRange.setEnabled(cb.isSelected() && layer.getLayerObjectCount(ViewerConstants.AMBIENT_TYPE_LOCAL) > 0);
        boolean state = cbLayerAmbientRange.isEnabled() && cbLayerAmbientRange.isSelected();
        layer.setItemTypeEnabled(ViewerConstants.AMBIENT_ITEM_RANGE, state);
      } else {
        cbLayerAmbientRange.setEnabled(false);
      }

      // Storing settings
      Settings.ShowAmbientRanges = cbLayerAmbientRange.isSelected();
    }
  }

  // Updates the state of real animation checkboxes and their associated functionality
  private void updateRealAnimation()
  {
    if (layerManager != null) {
      LayerAnimation layer = (LayerAnimation)layerManager.getLayer(LayerType.Animation);
      if (layer != null) {
        JCheckBox cb = cbLayers[LayerManager.getLayerTypeIndex(LayerType.Animation)];
        boolean enabled = cb.isEnabled() && cb.isSelected();
        cbLayerRealAnimation[0].setEnabled(enabled);
        cbLayerRealAnimation[1].setEnabled(enabled);
        boolean animEnabled = false;
        boolean animPlaying = false;
        if (enabled) {
          if (cbLayerRealAnimation[0].isSelected()) {
            animEnabled = true;
          } else if (cbLayerRealAnimation[1].isSelected()) {
            animEnabled = true;
            animPlaying = true;
          }
        }
        layer.setRealAnimationEnabled(animEnabled);
        layer.setRealAnimationPlaying(animPlaying);
      } else {
        cbLayerRealAnimation[0].setEnabled(false);
        cbLayerRealAnimation[1].setEnabled(false);
      }

      // Storing settings
      if (!cbLayerRealAnimation[0].isSelected() && !cbLayerRealAnimation[1].isSelected()) {
        Settings.ShowRealAnimations = ViewerConstants.ANIM_SHOW_NONE;
      } else if (cbLayerRealAnimation[0].isSelected() && !cbLayerRealAnimation[1].isSelected()) {
        Settings.ShowRealAnimations = ViewerConstants.ANIM_SHOW_STILL;
      } else if (!cbLayerRealAnimation[0].isSelected() && cbLayerRealAnimation[1].isSelected()) {
        Settings.ShowRealAnimations = ViewerConstants.ANIM_SHOW_ANIMATED;
      }
    }
  }

  // Show/hide items of the specified layer
  private void showLayer(LayerType layer, boolean visible)
  {
    if (layer != null && layerManager != null) {
      layerManager.setLayerVisible(layer, visible);
      // updating layer states
      int bit = 1 << LayerManager.getLayerTypeIndex(layer);
      if (visible) {
        Settings.LayerFlags |= bit;
      } else {
        Settings.LayerFlags &= ~bit;
      }
    }
  }


  // Adds items of all available layers to the map canvas.
  private void addLayerItems()
  {
    for (int i = 0; i < Settings.ListLayerOrder.size(); i++) {
      addLayerItems(Settings.ListLayerOrder.get(i));
    }
  }

  // Adds items of the specified layer to the map canvas.
  private void addLayerItems(LayerStackingType layer)
  {
    if (layer != null && layerManager != null) {
      List<LayerObject> list = layerManager.getLayerObjects(Settings.stackingToLayer(layer));
      if (list != null) {
        for (int i = 0; i < list.size(); i++) {
          addLayerItem(layer, list.get(i));
        }
      }
    }
  }

  // Adds items of a single layer object to the map canvas.
  private void addLayerItem(LayerStackingType layer, LayerObject object)
  {
    if (object != null) {
      // Dealing with ambient icons and ambient ranges separately
      if (layer == LayerStackingType.Ambient) {
        AbstractLayerItem item = object.getLayerItem(ViewerConstants.AMBIENT_ITEM_ICON);
        if (item != null) {
          rcCanvas.add(item);
        }
      } else if (layer == LayerStackingType.AmbientRange) {
        AbstractLayerItem item = object.getLayerItem(ViewerConstants.AMBIENT_ITEM_RANGE);
        if (item != null) {
          rcCanvas.add(item);
        }
      } else {
        AbstractLayerItem[] items = object.getLayerItems();
        if (items != null) {
          for (int i = 0; i < items.length; i++) {
            rcCanvas.add(items[i]);
          }
        }
      }
    }
  }


  // Removes all items of all available layers.
  private void removeLayerItems()
  {
    for (int i = 0; i < Settings.ListLayerOrder.size(); i++) {
      removeLayerItems(Settings.ListLayerOrder.get(i));
    }
  }

  // Removes all items of the specified layer.
  private void removeLayerItems(LayerStackingType layer)
  {
    if (layer != null && layerManager != null) {
      List<LayerObject> list = layerManager.getLayerObjects(Settings.stackingToLayer(layer));
      if (list != null) {
        for (int i = 0; i < list.size(); i++) {
          removeLayerItem(layer, list.get(i));
        }
      }
    }
  }

  // Removes items of a single layer object from the map canvas.
  private void removeLayerItem(LayerStackingType layer, LayerObject object)
  {
    if (object != null) {
      if (layer == LayerStackingType.Ambient) {
        AbstractLayerItem item = object.getLayerItem(ViewerConstants.AMBIENT_ITEM_ICON);
        rcCanvas.remove(item);
      } else if (layer == LayerStackingType.AmbientRange) {
        AbstractLayerItem item = object.getLayerItem(ViewerConstants.AMBIENT_ITEM_RANGE);
        if (item != null) {
          rcCanvas.remove(item);
        }
      } else {
        AbstractLayerItem[] items = object.getLayerItems();
        if (items != null) {
          for (int i = 0; i < items.length; i++) {
            rcCanvas.remove(items[i]);
          }
        }
      }
    }
  }


  // Re-orders layer items on the map using listLayer for determining priorities.
  private void orderLayerItems()
  {
    if (layerManager != null) {
      int index = 0;
      for (int i = 0; i < Settings.ListLayerOrder.size(); i++) {
        List<LayerObject> list = layerManager.getLayerObjects(Settings.stackingToLayer(Settings.ListLayerOrder.get(i)));
        if (list != null) {
          for (int j = 0; j < list.size(); j++) {
            if (Settings.ListLayerOrder.get(i) == LayerStackingType.AmbientRange) {
              // Special: process ambient ranges only
              LayerObjectAmbient obj = (LayerObjectAmbient)list.get(j);
              AbstractLayerItem item = obj.getLayerItem(ViewerConstants.AMBIENT_ITEM_RANGE);
              if (item != null) {
                rcCanvas.setComponentZOrder(item, index);
                index++;
              }
            } else if (Settings.ListLayerOrder.get(i) == LayerStackingType.Ambient) {
              // Special: process ambient icons only
              LayerObjectAmbient obj = (LayerObjectAmbient)list.get(j);
              AbstractLayerItem item = obj.getLayerItem(ViewerConstants.AMBIENT_ITEM_ICON);
              rcCanvas.setComponentZOrder(item, index);
              index++;
            } else {
              AbstractLayerItem[] items = list.get(j).getLayerItems();
              if (items != null) {
                for (int k = 0; k < items.length; k++) {
                  if (items[k].getParent() != null) {
                    rcCanvas.setComponentZOrder(items[k], index);
                    index++;
                  }
                }
              }
            }
          }
        }
      }
    }
  }


  // Updates all items of all available layers.
  private void updateLayerItems()
  {
    for (int i = 0; i < Settings.ListLayerOrder.size(); i++) {
      updateLayerItems(Settings.ListLayerOrder.get(i));
    }
  }

  // Updates the map locations of the items in the specified layer.
  private void updateLayerItems(LayerStackingType layer)
  {
    if (layer != null && layerManager != null) {
      List<LayerObject> list = layerManager.getLayerObjects(Settings.stackingToLayer(layer));
      if (list != null) {
        for (int i = 0; i < list.size(); i++) {
          updateLayerItem(list.get(i));
        }
      }
    }
  }

  // Updates the map locations of the items in the specified layer object.
  private void updateLayerItem(LayerObject object)
  {
    if (object != null) {
      object.update(getZoomFactor());
    }
  }

  // Update toolbar-related stuff
  private void updateToolBarButtons()
  {
    tbWed.setToolTipText(String.format("Edit WED structure (%1$s)", getCurrentWed().getName()));
  }

  // Initializes a new progress monitor instance
  private void initProgressMonitor(Component parent, String msg, String note, int maxProgress,
                                   int msDecide, int msWait)
  {
    if (parent == null) parent = NearInfinity.getInstance();
    if (maxProgress <= 0) maxProgress = 1;

    releaseProgressMonitor();
    pmMax = maxProgress;
    pmCur = 0;
    progress = new ProgressMonitor(parent, msg, note, 0, pmMax);
    progress.setMillisToDecideToPopup(msDecide);
    progress.setMillisToPopup(msWait);
    progress.setProgress(pmCur);
  }

  // Closes the current progress monitor
  private void releaseProgressMonitor()
  {
    if (progress != null) {
      progress.close();
      progress = null;
    }
  }

  // Advances the current progress monitor by one and adds the specified note
  private void advanceProgressMonitor(String note)
  {
    if (progress != null) {
      if (pmCur < pmMax) {
        pmCur++;
        if (note != null) {
          progress.setNote(note);
        }
        progress.setProgress(pmCur);
      }
    }
  }

  // Returns whether a progress monitor is currently active
  private boolean isProgressMonitorActive()
  {
    return progress != null;
  }

  // Shows settings dialog and updates respective controls if needed
  private void viewSettings()
  {
    SettingsDialog vs = new SettingsDialog(this);
    if (vs.settingsChanged()) {
      applySettings();
    }
    vs = null;
  }

  // Applies current global area viewer settings
  private void applySettings()
  {
    // applying layer stacking order
    orderLayerItems();
    // applying interpolation settings to map
    rcCanvas.setInterpolationType(Settings.InterpolationMap);
    rcCanvas.setForcedInterpolation(Settings.InterpolationMap != ViewerConstants.INTERPOLATION_AUTO);
    if (layerManager != null) {
      // applying animation frame settings
      ((LayerAnimation)layerManager.getLayer(LayerType.Animation)).setRealAnimationFrameState(Settings.ShowFrame);
      // applying interpolation settings to animations
      switch (Settings.InterpolationAnim) {
        case ViewerConstants.INTERPOLATION_AUTO:
          layerManager.setRealAnimationForcedInterpolation(false);
          break;
        case ViewerConstants.INTERPOLATION_BILINEAR:
          layerManager.setRealAnimationForcedInterpolation(true);
          layerManager.setRealAnimationInterpolation(ViewerConstants.TYPE_BILINEAR);
          break;
        case ViewerConstants.INTERPOLATION_NEARESTNEIGHBOR:
          layerManager.setRealAnimationForcedInterpolation(true);
          layerManager.setRealAnimationInterpolation(ViewerConstants.TYPE_NEAREST_NEIGHBOR);
          break;
      }
      // applying frame rate to animated overlays
      int interval = (int)(1000.0 / Settings.FrameRateOverlays);
      if (interval != timerOverlays.getDelay()) {
        timerOverlays.setDelay(interval);
      }
      // applying frame rate to background animations
      layerManager.setRealAnimationFrameRate(Settings.FrameRateAnimations);
    }
  }


//----------------------------- INNER CLASSES -----------------------------

  // Associates a menu item with a layer item
  private class LayerMenuItem extends JMenuItem
  {
    private AbstractLayerItem layerItem;

    public LayerMenuItem(String text, AbstractLayerItem item)
    {
      super(text);
      layerItem = item;
    }

    public AbstractLayerItem getLayerItem()
    {
      return layerItem;
    }
  }


  // Handles map-specific properties
  private static class Map
  {
    private static final int MAP_DAY    = 0;
    private static final int MAP_NIGHT  = 1;

    private final Window parent;
    private final WedResource[] wed = new WedResource[2];
    private final AbstractLayerItem[] wedItem = new IconLayerItem[]{null, null};

    private AreResource are;
    private boolean hasDayNight, hasExtendedNight;
    private AbstractLayerItem areItem, songItem, restItem;

    public Map(Window parent, AreResource are)
    {
      this.parent = parent;
      this.are = are;
      init();
    }

    /**
     * Removes resources from memory.
     */
    public void clear()
    {
      songItem = null;
      restItem = null;
      are = null;
      areItem = null;
      closeWed(MAP_DAY, false);
      wed[MAP_DAY] = null;
      closeWed(MAP_NIGHT, false);
      wed[MAP_NIGHT] = null;
      wedItem[MAP_DAY] = null;
      wedItem[MAP_NIGHT] = null;
    }

    // Returns the current AreResource instance
    public AreResource getAre()
    {
      return are;
    }

    // Returns the WedResource instance of day or night map
    public WedResource getWed(int dayNight)
    {
      switch (dayNight) {
        case MAP_DAY: return wed[MAP_DAY];
        case MAP_NIGHT: return wed[MAP_NIGHT];
        default: return null;
      }
    }

    /**
     * Attempts to close the specified WED. If changes have been done, a dialog asks for saving.
     * @param dayNight Either MAP_DAY or MAP_NIGHT.
     * @param allowCancel Indicates whether to allow cancelling the saving process.
     * @return <code>true</code> if the resource has been closed, <code>false</code> otherwise (e.g.
     *         if the user chooses to cancel saving changes.)
     */
    public boolean closeWed(int dayNight, boolean allowCancel)
    {
      boolean bRet = false;
      dayNight = (dayNight == MAP_NIGHT) ? MAP_NIGHT : MAP_DAY;
      if (wed[dayNight] != null) {
        if (wed[dayNight].hasStructChanged()) {
          File output;
          if (wed[dayNight].getResourceEntry() instanceof BIFFResourceEntry) {
            output = NIFile.getFile(ResourceFactory.getRootDir(),
                                    ResourceFactory.OVERRIDEFOLDER + File.separatorChar +
                                    wed[dayNight].getResourceEntry().toString());
          } else {
            output = wed[dayNight].getResourceEntry().getActualFile();
          }
          int optionIndex = allowCancel ? 1 : 0;
          int optionType = allowCancel ? JOptionPane.YES_NO_CANCEL_OPTION : JOptionPane.YES_NO_OPTION;
          String options[][] = { {"Save changes", "Discard changes"}, {"Save changes", "Discard changes", "Cancel"} };
          int result = JOptionPane.showOptionDialog(parent, "Save changes to " + output + '?', "Resource changed",
                                                    optionType, JOptionPane.WARNING_MESSAGE, null,
                                                    options[optionIndex], options[optionIndex][0]);
          if (result == 0) {
            ResourceFactory.getInstance().saveResource((Resource)wed[dayNight], parent);
          }
          if (result != 2) {
            wed[dayNight].setStructChanged(false);
          }
          bRet = (result != 2);
        } else {
          bRet = true;
        }
        if (bRet && wed[dayNight].getViewer() != null) {
          wed[dayNight].getViewer().close();
        }
      }
      return bRet;
    }

    /**
     * Reloads the specified WED resource.
     * @param dayNight The WED resource to load.
     */
    public void reloadWed(int dayNight)
    {
      if (are != null) {
        dayNight = (dayNight == MAP_NIGHT) ? MAP_NIGHT : MAP_DAY;
        String wedName = "";
        ResourceRef wedRef = (ResourceRef)are.getAttribute("WED resource");
        if (wedRef != null) {
          wedName = wedRef.getResourceName();
          if ("None".equalsIgnoreCase(wedName)) {
            wedName = "";
          }

          if (dayNight == MAP_DAY) {
            if (!wedName.isEmpty()) {
              try {
                wed[MAP_DAY] = new WedResource(ResourceFactory.getInstance().getResourceEntry(wedName));
              } catch (Exception e) {
                wed[MAP_DAY] = null;
              }
            } else {
              wed[MAP_DAY] = null;
            }

            if (wed[MAP_DAY] != null) {
              wedItem[MAP_DAY] = new IconLayerItem(new Point(), wed[MAP_DAY], wed[MAP_DAY].getName());
              wedItem[MAP_DAY].setVisible(false);
            }
          } else {
            // getting extended night map
            if (hasExtendedNight && !wedName.isEmpty()) {
              int pos = wedName.lastIndexOf('.');
              if (pos > 0) {
                String wedNameNight = wedName.substring(0, pos) + "N" + wedName.substring(pos);
                try {
                  wed[MAP_NIGHT] = new WedResource(ResourceFactory.getInstance().getResourceEntry(wedNameNight));
                } catch (Exception e) {
                  wed[MAP_NIGHT] = wed[MAP_DAY];
                }
              } else {
                wed[MAP_NIGHT] = wed[MAP_DAY];
              }
            } else {
              wed[MAP_NIGHT] = wed[MAP_DAY];
            }

            if (wed[MAP_NIGHT] != null) {
              wedItem[MAP_NIGHT] = new IconLayerItem(new Point(), wed[MAP_NIGHT], wed[MAP_NIGHT].getName());
              wedItem[MAP_NIGHT].setVisible(false);
            }
          }
        }
      }
    }

    // Returns the pseudo layer item for the AreResource structure
    public AbstractLayerItem getAreItem()
    {
      return areItem;
    }

    // Returns the pseudo layer item for the WedResource structure of the selected day time
    public AbstractLayerItem getWedItem(int dayNight)
    {
      if (dayNight == MAP_NIGHT) {
        return wedItem[MAP_NIGHT];
      } else {
        return wedItem[MAP_DAY];
      }
    }

    // Returns the pseudo layer item for the ARE's song structure
    public AbstractLayerItem getSongItem()
    {
      return songItem;
    }

    // Returns the pseudo layer item for the ARE's rest encounter structure
    public AbstractLayerItem getRestItem()
    {
      return restItem;
    }

    // Returns whether the current map supports day/twilight/night settings
    public boolean hasDayNight()
    {
      return hasDayNight;
    }

    // Returns true if the current map has separate WEDs for day/night
    public boolean hasExtendedNight()
    {
      return hasExtendedNight;
    }


    private void init()
    {
      if (are != null) {
        // fetching important flags
        Flag flags = (Flag)are.getAttribute("Location");
        if (flags != null) {
          if (ResourceFactory.getGameID() == ResourceFactory.ID_TORMENT) {
            hasDayNight = flags.isFlagSet(10);
            hasExtendedNight = false;
          } else {
            hasDayNight = flags.isFlagSet(1);
            hasExtendedNight = flags.isFlagSet(6);
          }
        }

        // initializing pseudo layer items
        areItem = new IconLayerItem(new Point(), are, are.getName());
        areItem.setVisible(false);

        Song song = (Song)are.getAttribute("Songs");
        if (song != null) {
          songItem = new IconLayerItem(new Point(), song, "");
          songItem.setVisible(false);
        }

        RestSpawn rest = (RestSpawn)are.getAttribute("Rest encounters");
        if (rest != null) {
          restItem = new IconLayerItem(new Point(), rest, "");
        }

        // getting associated WED resources
        reloadWed(MAP_DAY);
        reloadWed(MAP_NIGHT);
      }
    }
  }


  // Defines a panel providing controls for setting day times (either by hour or by general day time)
  private static final class DayTimePanel extends JPanel implements ActionListener, ChangeListener
  {
    private final List<ChangeListener> listeners = new ArrayList<ChangeListener>();
    private final JRadioButton[] rbDayTime = new JRadioButton[3];
    private final ButtonPopupWindow bpwDayTime;

    private JSlider sHours;

    public DayTimePanel(ButtonPopupWindow bpw, int hour)
    {
      super(new BorderLayout());
      bpwDayTime = bpw;
      init(hour);
    }

    public int getHour()
    {
      return sHours.getValue();
    }

    public void setHour(int hour)
    {
      while (hour < 0) { hour += 24; }
      hour %= 24;
      if (hour != sHours.getValue()) {
        sHours.setValue(hour);
        rbDayTime[ViewerConstants.getDayTime(hour)].setSelected(true);
        fireStateChanged();
      }
    }

    /**
     * Adds a ChangeListener to the slider.
     * @param l the ChangeListener to add
     */
    public void addChangeListener(ChangeListener l)
    {
      if (l != null) {
        if (!listeners.contains(l)) {
          listeners.add(l);
        }
      }
    }

    /**
     * Removes a ChangeListener from the slider.
     * @param l the ChangeListener to remove
     */
    public void removeChangeListener(ChangeListener l)
    {
      if (l != null) {
        int index = listeners.indexOf(l);
        if (index >= 0) {
          listeners.remove(index);
        }
      }
    }

    /**
     * Returns an array of all the ChangeListeners added to this JSlider with addChangeListener().
     * @return All of the ChangeListeners added or an empty array if no listeners have been added.
     */
    public ChangeListener[] getChangeListeners()
    {
      ChangeListener[] retVal = new ChangeListener[listeners.size()];
      for (int i = 0; i < listeners.size(); i++) {
        retVal[i] = listeners.get(i);
      }
      return retVal;
    }

    // --------------------- Begin Interface ActionListener ---------------------

    @Override
    public void actionPerformed(ActionEvent event)
    {
      for (int i = 0; i < rbDayTime.length; i++) {
        if (event.getSource() == rbDayTime[i]) {
          int hour = ViewerConstants.getHourOf(i);
          if (hour != sHours.getValue()) {
            sHours.setValue(hour);
          }
          break;
        }
      }
    }

    // --------------------- End Interface ActionListener ---------------------

    // --------------------- Begin Interface ChangeListener ---------------------

    @Override
    public void stateChanged(ChangeEvent event)
    {
      if (event.getSource() == sHours) {
        if (!sHours.getValueIsAdjusting()) {
          int dt = ViewerConstants.getDayTime(sHours.getValue());
          if (!rbDayTime[dt].isSelected()) {
            rbDayTime[dt].setSelected(true);
          }
          updateButton();
          fireStateChanged();
        }
      }
    }

    // --------------------- End Interface ChangeListener ---------------------

    // Fires a stateChanged event for all registered listeners
    private void fireStateChanged()
    {
      ChangeEvent event = new ChangeEvent(this);
      for (int i = 0; i < listeners.size(); i++) {
        listeners.get(i).stateChanged(event);
      }
    }

    // Updates the text of the parent button
    private void updateButton()
    {
      final String[] dayTime = new String[]{"Day", "Twilight", "Night"};

      int hour = sHours.getValue();
      String desc = dayTime[ViewerConstants.getDayTime(hour)];
      if (bpwDayTime != null) {
        bpwDayTime.setText(String.format("Time (%1$02d:00 - %2$s)", hour, desc));
      }
    }

    private void init(int hour)
    {
      while (hour < 0) { hour += 24; }
      hour %= 24;
      int dayTime = ViewerConstants.getDayTime(hour);

      ButtonGroup bg = new ButtonGroup();
      String s = String.format("Day (%1$02d:00)", ViewerConstants.getHourOf(ViewerConstants.LIGHTING_DAY));
      rbDayTime[ViewerConstants.LIGHTING_DAY] = new JRadioButton(s, (dayTime == ViewerConstants.LIGHTING_DAY));
      rbDayTime[ViewerConstants.LIGHTING_DAY].addActionListener(this);
      bg.add(rbDayTime[ViewerConstants.LIGHTING_DAY]);
      s = String.format("Twilight (%1$02d:00)", ViewerConstants.getHourOf(ViewerConstants.LIGHTING_TWILIGHT));
      rbDayTime[ViewerConstants.LIGHTING_TWILIGHT] = new JRadioButton(s, (dayTime == ViewerConstants.LIGHTING_TWILIGHT));
      rbDayTime[ViewerConstants.LIGHTING_TWILIGHT].addActionListener(this);
      bg.add(rbDayTime[ViewerConstants.LIGHTING_TWILIGHT]);
      s = String.format("Night (%1$02d:00)", ViewerConstants.getHourOf(ViewerConstants.LIGHTING_NIGHT));
      rbDayTime[ViewerConstants.LIGHTING_NIGHT] = new JRadioButton(s, (dayTime == ViewerConstants.LIGHTING_NIGHT));
      rbDayTime[ViewerConstants.LIGHTING_NIGHT].addActionListener(this);
      bg.add(rbDayTime[ViewerConstants.LIGHTING_NIGHT]);

      Hashtable<Integer, JLabel> table = new Hashtable<Integer, JLabel>();
      for (int i = 0; i < 24; i += 4) {
        table.put(Integer.valueOf(i), new JLabel(String.format("%1$02d:00", i)));
      }
      sHours = new JSlider(0, 23, hour);
      sHours.addChangeListener(this);
      sHours.setSnapToTicks(true);
      sHours.setLabelTable(table);
      sHours.setPaintLabels(true);
      sHours.setMinorTickSpacing(1);
      sHours.setMajorTickSpacing(4);
      sHours.setPaintTicks(true);
      sHours.setPaintTrack(true);
      Dimension dim = sHours.getPreferredSize();
      sHours.setPreferredSize(new Dimension((dim.width*3)/2, dim.height));

      GridBagConstraints c = new GridBagConstraints();
      JPanel pHours = new JPanel(new GridBagLayout());
      pHours.setBorder(BorderFactory.createTitledBorder("By hour: "));
      c = setGBC(c, 0, 0, 1, 1, 1.0, 0.0, GridBagConstraints.LINE_START,
          GridBagConstraints.HORIZONTAL, new Insets(4, 4, 4, 4), 0, 0);
      pHours.add(sHours, c);

      JPanel pTime = new JPanel(new GridBagLayout());
      pTime.setBorder(BorderFactory.createTitledBorder("By lighting condition: "));
      c = setGBC(c, 0, 0, 1, 1, 1.0, 0.0, GridBagConstraints.LINE_START,
                 GridBagConstraints.NONE, new Insets(4, 8, 4, 0), 0, 0);
      pTime.add(rbDayTime[ViewerConstants.LIGHTING_DAY], c);
      c = setGBC(c, 1, 0, 1, 1, 1.0, 0.0, GridBagConstraints.LINE_START,
                 GridBagConstraints.NONE, new Insets(4, 8, 4, 0), 0, 0);
      pTime.add(rbDayTime[ViewerConstants.LIGHTING_TWILIGHT], c);
      c = setGBC(c, 2, 0, 1, 1, 1.0, 0.0, GridBagConstraints.LINE_START,
                 GridBagConstraints.NONE, new Insets(4, 8, 4, 8), 0, 0);
      pTime.add(rbDayTime[ViewerConstants.LIGHTING_NIGHT], c);

      JPanel pMain = new JPanel(new GridBagLayout());
      c = setGBC(c, 0, 0, 1, 1, 1.0, 0.0, GridBagConstraints.FIRST_LINE_START,
                 GridBagConstraints.HORIZONTAL, new Insets(4, 4, 0, 4), 0, 0);
      pMain.add(pHours, c);
      c = setGBC(c, 0, 1, 1, 1, 1.0, 0.0, GridBagConstraints.FIRST_LINE_START,
                 GridBagConstraints.HORIZONTAL, new Insets(4, 4, 4, 4), 0, 0);
      pMain.add(pTime, c);

      add(pMain, BorderLayout.CENTER);

      updateButton();
    }
  }
}
