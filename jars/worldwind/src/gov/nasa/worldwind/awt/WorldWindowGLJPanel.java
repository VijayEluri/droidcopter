/*
Copyright (C) 2001, 2006 United States Government
as represented by the Administrator of the
National Aeronautics and Space Administration.
All Rights Reserved.
*/
package gov.nasa.worldwind.awt;

import gov.nasa.worldwind.*;
import gov.nasa.worldwind.avlist.*;
import gov.nasa.worldwind.cache.*;
import gov.nasa.worldwind.event.*;
import gov.nasa.worldwind.exception.WWRuntimeException;
import gov.nasa.worldwind.geom.Position;
import gov.nasa.worldwind.pick.PickedObjectList;
import gov.nasa.worldwind.util.*;
import gov.nasa.worldwind.View;

import javax.media.opengl.*;
import java.awt.*;
import java.beans.*;
import java.util.*;

/**
 */
public class WorldWindowGLJPanel extends GLJPanel implements WorldWindow, PropertyChangeListener
{
    private static final GLCapabilities caps = new GLCapabilities();

    static
    {
        caps.setAlphaBits(8);
        caps.setRedBits(8);
        caps.setGreenBits(8);
        caps.setBlueBits(8);
        caps.setDepthBits(24);
    }

    private final WorldWindowGLDrawable wwd; // WorldWindow interface delegates to wwd

    /**
     * Constructs a new <code>WorldWindowGLCanvas</code> window on the default graphics device.
     */
    public WorldWindowGLJPanel()
    {
        super(caps);
        try
        {
            this.wwd = ((WorldWindowGLDrawable) WorldWind.createConfigurationComponent(AVKey.WORLD_WINDOW_CLASS_NAME));
            this.wwd.initDrawable(this);
            this.wwd.initTextureCache(createTextureCache());
            this.createView();
            this.createDefaultInputHandler();
            WorldWind.addPropertyChangeListener(WorldWind.SHUTDOWN_EVENT, this);
            this.wwd.endInitalization();
        }
        catch (Exception e)
        {
            String message = Logging.getMessage("Awt.WorldWindowGLSurface.UnabletoCreateWindow");
            Logging.logger().severe(message);
            throw new WWRuntimeException(message, e);
        }
    }

    private static final long FALLBACK_TEXTURE_CACHE_SIZE = 60000000;

    private static TextureCache createTextureCache()
    {
        long cacheSize = Configuration.getLongValue(AVKey.TEXTURE_CACHE_SIZE, FALLBACK_TEXTURE_CACHE_SIZE);
        return new BasicTextureCache((long) (0.8 * cacheSize), cacheSize);
    }

    /**
     * Constructs a new <code>WorldWindowGLJPanel</code> window on the default graphics device that will share graphics
     * resources with another <code>WorldWindowGLJPanel</code> window. The other window, <code>sharewith</code>, may not
     * be null
     *
     * @param shareWith a <code>WorldWindowGLJPanel</code> with which to share graphics resources.
     * @throws NullPointerException if shareWith is null
     * @see GLCanvas#GLCanvas(GLCapabilities,GLCapabilitiesChooser,GLContext,GraphicsDevice)
     */
    public WorldWindowGLJPanel(WorldWindowGLCanvas shareWith)
    {
        super(caps, null, shareWith.getContext());
        try
        {
            this.wwd = ((WorldWindowGLDrawable) WorldWind.createConfigurationComponent(AVKey.WORLD_WINDOW_CLASS_NAME));
            this.wwd.initDrawable(this);
            this.wwd.initTextureCache(shareWith.getTextureCache());
            this.createView();
            this.createDefaultInputHandler();
            WorldWind.addPropertyChangeListener(WorldWind.SHUTDOWN_EVENT, this);
            this.wwd.endInitalization();
        }
        catch (Exception e)
        {
            String message = Logging.getMessage("Awt.WorldWindowGLSurface.UnabletoCreateWindow");
            Logging.logger().severe(message);
            throw new WWRuntimeException(message, e);
        }
    }

    /**
     * Constructs a new <code>WorldWindowGLJPanel</code> window that will share graphics resources with another
     * <code>WorldWindowGLJPanel</code> window. The new window is created on the specified graphics device. Neither
     * <code> shareWith</code> or <code>device</code> may be null.
     *
     * @param shareWith a <code>WorldWindowGLCanvas</code> with which to share graphics resources.
     * @param device    the <code>GraphicsDevice</code> on which to create the window.
     * @throws NullPointerException     if <code>shareWith</code> is null
     * @throws IllegalArgumentException if <code>deevice</code> is null
     * @see GLCanvas#GLCanvas(GLCapabilities,GLCapabilitiesChooser,GLContext,GraphicsDevice)
     */
    public WorldWindowGLJPanel(WorldWindowGLCanvas shareWith, java.awt.GraphicsDevice device)
    {
        super(null, null, shareWith.getContext());

        if (device == null)
        {
            String msg = Logging.getMessage("nullValue.DeviceIsNull");
            Logging.logger().severe(msg);
            throw new IllegalArgumentException(msg);
        }
        try
        {
            this.wwd = ((WorldWindowGLDrawable) WorldWind.createConfigurationComponent(AVKey.WORLD_WINDOW_CLASS_NAME));
            this.wwd.initDrawable(this);
            this.wwd.initTextureCache(shareWith.getTextureCache());
            this.createView();
            this.createDefaultInputHandler();
            WorldWind.addPropertyChangeListener(WorldWind.SHUTDOWN_EVENT, this);
            this.wwd.endInitalization();
        }
        catch (Exception e)
        {
            String message = Logging.getMessage("Awt.WorldWindowGLSurface.UnabletoCreateWindow");
            Logging.logger().severe(message);
            throw new WWRuntimeException(message, e);
        }
    }

    public void propertyChange(PropertyChangeEvent evt)
    {
        //noinspection StringEquality
        if (evt.getPropertyName() == WorldWind.SHUTDOWN_EVENT)
            this.shutdown();
    }

    public void shutdown()
    {
        WorldWind.removePropertyChangeListener(WorldWind.SHUTDOWN_EVENT, this);
        this.wwd.shutdown();
    }

    private void createView()
    {
        this.setView((View) WorldWind.createConfigurationComponent(AVKey.VIEW_CLASS_NAME));
    }

    private void createDefaultInputHandler()
    {
        this.setInputHandler((InputHandler) WorldWind.createConfigurationComponent(AVKey.INPUT_HANDLER_CLASS_NAME));
    }

    public InputHandler getInputHandler()
    {
        return this.wwd.getInputHandler();
    }

    public void setInputHandler(InputHandler inputHandler)
    {
        if (this.wwd.getInputHandler() != null)
            this.wwd.getInputHandler().setEventSource(null); // remove this window as a source of events

        this.wwd.setInputHandler(inputHandler != null ? inputHandler : new NoOpInputHandler());
        if (inputHandler != null)
            inputHandler.setEventSource(this);
    }

    public SceneController getSceneController()
    {
        return this.wwd.getSceneController();
    }

    public TextureCache getTextureCache()
    {
        return this.wwd.getTextureCache();
    }

    public void redraw()
    {
        this.repaint();
    }

    public void redrawNow()
    {
        this.wwd.redrawNow();
    }

    public void setModel(Model model)
    {
        // null models are permissible
        this.wwd.setModel(model);
    }

    public Model getModel()
    {
        return this.wwd.getModel();
    }

    public void setView(View view)
    {
        // null views are permissible
        if (view != null)
            this.wwd.setView(view);
    }

    public View getView()
    {
        return this.wwd.getView();
    }

    public void setModelAndView(Model model, View view)
    {   // null models/views are permissible
        this.setModel(model);
        this.setView(view);
    }

    public void addRenderingListener(RenderingListener listener)
    {
        this.wwd.addRenderingListener(listener);
    }

    public void removeRenderingListener(RenderingListener listener)
    {
        this.wwd.removeRenderingListener(listener);
    }

    public void addSelectListener(SelectListener listener)
    {
        this.wwd.getInputHandler().addSelectListener(listener);
        this.wwd.addSelectListener(listener);
    }

    public void removeSelectListener(SelectListener listener)
    {
        this.wwd.getInputHandler().removeSelectListener(listener);
        this.wwd.removeSelectListener(listener);
    }

    public void addPositionListener(PositionListener listener)
    {
        this.wwd.addPositionListener(listener);
    }

    public void removePositionListener(PositionListener listener)
    {
        this.wwd.removePositionListener(listener);
    }

    public void addRenderingExceptionListener(RenderingExceptionListener listener)
    {
        this.wwd.addRenderingExceptionListener(listener);
    }

    public void removeRenderingExceptionListener(RenderingExceptionListener listener)
    {
        this.wwd.removeRenderingExceptionListener(listener);
    }

    public Position getCurrentPosition()
    {
        return this.wwd.getCurrentPosition();
    }

    public PickedObjectList getObjectsAtCurrentPosition()
    {
        return this.wwd.getSceneController() != null ? this.wwd.getSceneController().getPickedObjectList() : null;
    }

    public Object setValue(String key, Object value)
    {
        return this.wwd.setValue(key, value);
    }

    public AVList setValues(AVList avList)
    {
        return this.wwd.setValues(avList);
    }

    public Object getValue(String key)
    {
        return this.wwd.getValue(key);
    }

    public Collection<Object> getValues()
    {
        return this.wwd.getValues();
    }

    public Set<Map.Entry<String, Object>> getEntries()
    {
        return this.wwd.getEntries();
    }

    public String getStringValue(String key)
    {
        return this.wwd.getStringValue(key);
    }

    public boolean hasKey(String key)
    {
        return this.wwd.hasKey(key);
    }

    public Object removeKey(String key)
    {
        return this.wwd.removeKey(key);
    }

    @Override
    public synchronized void addPropertyChangeListener(PropertyChangeListener listener)
    {
        super.addPropertyChangeListener(listener);
        this.wwd.addPropertyChangeListener(listener);
    }

    @Override
    public synchronized void addPropertyChangeListener(String propertyName, PropertyChangeListener listener)
    {
        super.addPropertyChangeListener(propertyName, listener);
        this.wwd.addPropertyChangeListener(propertyName, listener);
    }

    @Override
    public synchronized void removePropertyChangeListener(PropertyChangeListener listener)
    {
        super.removePropertyChangeListener(listener);
        this.wwd.removePropertyChangeListener(listener);
    }

    @Override
    public synchronized void removePropertyChangeListener(String propertyName, PropertyChangeListener listener)
    {
        super.removePropertyChangeListener(listener);
        this.wwd.removePropertyChangeListener(listener);
    }

    @Override
    public void firePropertyChange(String propertyName, Object oldValue, Object newValue)
    {
        super.firePropertyChange(propertyName, oldValue, newValue);
    }

    public void firePropertyChange(PropertyChangeEvent propertyChangeEvent)
    {
        this.wwd.firePropertyChange(propertyChangeEvent);
    }

    public AVList copy()
    {
        return this.wwd.copy();
    }

    public AVList clearList()
    {
        return this.wwd.clearList();
    }

    public void setPerFrameStatisticsKeys(Set<String> keys)
    {
        this.wwd.setPerFrameStatisticsKeys(keys);
    }

    public Collection<PerformanceStatistic> getPerFrameStatistics()
    {
        return this.wwd.getPerFrameStatistics();
    }
}
