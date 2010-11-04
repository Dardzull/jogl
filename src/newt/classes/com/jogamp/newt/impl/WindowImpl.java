/*
 * Copyright (c) 2008 Sun Microsystems, Inc. All Rights Reserved.
   Copyright (c) 2010 JogAmp Community. All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 * 
 * - Redistribution of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 * 
 * - Redistribution in binary form must reproduce the above copyright
 *   notice, this list of conditions and the following disclaimer in the
 *   documentation and/or other materials provided with the distribution.
 * 
 * Neither the name of Sun Microsystems, Inc. or the names of
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 * 
 * This software is provided "AS IS," without a warranty of any kind. ALL
 * EXPRESS OR IMPLIED CONDITIONS, REPRESENTATIONS AND WARRANTIES,
 * INCLUDING ANY IMPLIED WARRANTY OF MERCHANTABILITY, FITNESS FOR A
 * PARTICULAR PURPOSE OR NON-INFRINGEMENT, ARE HEREBY EXCLUDED. SUN
 * MICROSYSTEMS, INC. ("SUN") AND ITS LICENSORS SHALL NOT BE LIABLE FOR
 * ANY DAMAGES SUFFERED BY LICENSEE AS A RESULT OF USING, MODIFYING OR
 * DISTRIBUTING THIS SOFTWARE OR ITS DERIVATIVES. IN NO EVENT WILL SUN OR
 * ITS LICENSORS BE LIABLE FOR ANY LOST REVENUE, PROFIT OR DATA, OR FOR
 * DIRECT, INDIRECT, SPECIAL, CONSEQUENTIAL, INCIDENTAL OR PUNITIVE
 * DAMAGES, HOWEVER CAUSED AND REGARDLESS OF THE THEORY OF LIABILITY,
 * ARISING OUT OF THE USE OF OR INABILITY TO USE THIS SOFTWARE, EVEN IF
 * SUN HAS BEEN ADVISED OF THE POSSIBILITY OF SUCH DAMAGES.
 * 
 */

package com.jogamp.newt.impl;

import com.jogamp.common.util.ReflectionUtil;
import com.jogamp.newt.NewtFactory;
import com.jogamp.newt.Display;
import com.jogamp.newt.Screen;
import com.jogamp.newt.Window;
import com.jogamp.common.util.locks.RecursiveLock;
import com.jogamp.newt.ScreenMode;
import com.jogamp.newt.event.KeyEvent;
import com.jogamp.newt.event.KeyListener;
import com.jogamp.newt.event.MouseEvent;
import com.jogamp.newt.event.MouseListener;
import com.jogamp.newt.event.NEWTEvent;
import com.jogamp.newt.event.NEWTEventConsumer;
import com.jogamp.newt.event.ScreenModeListener;
import com.jogamp.newt.event.WindowEvent;
import com.jogamp.newt.event.WindowListener;
import com.jogamp.newt.event.WindowUpdateEvent;

import java.util.ArrayList;
import java.lang.reflect.Method;
import javax.media.nativewindow.AbstractGraphicsConfiguration;
import javax.media.nativewindow.AbstractGraphicsDevice;
import javax.media.nativewindow.Capabilities;
import javax.media.nativewindow.NativeSurface;
import javax.media.nativewindow.NativeWindow;
import javax.media.nativewindow.NativeWindowException;
import javax.media.nativewindow.NativeWindowFactory;
import javax.media.nativewindow.SurfaceUpdatedListener;
import javax.media.nativewindow.util.DimensionReadOnly;
import javax.media.nativewindow.util.Insets;
import javax.media.nativewindow.util.Point;
import javax.media.nativewindow.util.Rectangle;

public abstract class WindowImpl implements Window, NEWTEventConsumer, ScreenModeListener
{
    public static final boolean DEBUG_TEST_REPARENT_INCOMPATIBLE = Debug.isPropertyDefined("newt.test.Window.reparent.incompatible", true);
    
    // Workaround for initialization order problems on Mac OS X
    // between native Newt and (apparently) Fmod -- if Fmod is
    // initialized first then the connection to the window server
    // breaks, leading to errors from deep within the AppKit
    public static void init(String type) {
        if (NativeWindowFactory.TYPE_MACOSX.equals(type)) {
            try {
                getWindowClass(type);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    //
    // Construction Methods
    //

    private static Class getWindowClass(String type) 
        throws ClassNotFoundException 
    {
        Class windowClass = NewtFactory.getCustomClass(type, "Window");
        if(null==windowClass) {
            if (NativeWindowFactory.TYPE_EGL.equals(type)) {
                windowClass = Class.forName("com.jogamp.newt.impl.opengl.kd.KDWindow");
            } else if (NativeWindowFactory.TYPE_WINDOWS.equals(type)) {
                windowClass = Class.forName("com.jogamp.newt.impl.windows.WindowsWindow");
            } else if (NativeWindowFactory.TYPE_MACOSX.equals(type)) {
                windowClass = Class.forName("com.jogamp.newt.impl.macosx.MacWindow");
            } else if (NativeWindowFactory.TYPE_X11.equals(type)) {
                windowClass = Class.forName("com.jogamp.newt.impl.x11.X11Window");
            } else if (NativeWindowFactory.TYPE_AWT.equals(type)) {
                windowClass = Class.forName("com.jogamp.newt.impl.awt.AWTWindow");
            } else {
                throw new NativeWindowException("Unknown window type \"" + type + "\"");
            }
        }
        return windowClass;
    }

    public static WindowImpl create(NativeWindow parentWindow, long parentWindowHandle, Screen screen, Capabilities caps) {
        try {
            Class windowClass;
            if(caps.isOnscreen()) {
                windowClass = getWindowClass(screen.getDisplay().getType());
            } else {
                windowClass = OffscreenWindow.class;
            }
            WindowImpl window = (WindowImpl) windowClass.newInstance();
            window.invalidate(true);
            window.parentWindow = parentWindow;
            window.parentWindowHandle = parentWindowHandle;
            window.screen = (ScreenImpl) screen;
            window.caps = (Capabilities)caps.clone();
            window.setUndecorated(0!=parentWindowHandle);
            return window;
        } catch (Throwable t) {
            t.printStackTrace();
            throw new NativeWindowException(t);
        }
    }

    public static WindowImpl create(Object[] cstrArguments, Screen screen, Capabilities caps) {
        try {
            Class windowClass = getWindowClass(screen.getDisplay().getType());
            Class[] cstrArgumentTypes = getCustomConstructorArgumentTypes(windowClass);
            if(null==cstrArgumentTypes) {
                throw new NativeWindowException("WindowClass "+windowClass+" doesn't support custom arguments in constructor");
            }
            int argsChecked = verifyConstructorArgumentTypes(cstrArgumentTypes, cstrArguments);
            if ( argsChecked < cstrArguments.length ) {
                throw new NativeWindowException("WindowClass "+windowClass+" constructor mismatch at argument #"+argsChecked+"; Constructor: "+getTypeStrList(cstrArgumentTypes)+", arguments: "+getArgsStrList(cstrArguments));
            }
            WindowImpl window = (WindowImpl) ReflectionUtil.createInstance( windowClass, cstrArgumentTypes, cstrArguments ) ;
            window.invalidate(true);
            window.screen = (ScreenImpl) screen;
            window.caps = (Capabilities)caps.clone();
            return window;
        } catch (Throwable t) {
            throw new NativeWindowException(t);
        }
    }

    public static interface LifecycleHook {
        /** 
         * Invoked after Window setVisible, 
         * allows allocating resources depending on the native Window.
         * Called from EDT.
         */
        void setVisibleAction(boolean visible, boolean nativeWindowCreated);

        /** 
         * Invoked before Window destroy action, 
         * allows releasing of resources depending on the native Window.<br>
         * Surface not locked yet.<br>
         * Called not necessarily from EDT.
         */
        void destroyActionPreLock(boolean unrecoverable);

        /**
         * Invoked before Window destroy action,
         * allows releasing of resources depending on the native Window.<br>
         * Surface locked.<br>
         * Called from EDT.
         */
        void destroyActionInLock(boolean unrecoverable);

        /**
         * Invoked for expensive modifications, ie while reparenting and ScreenMode change.<br>
         * No lock is hold when invoked.<br>
         *
         * @see #resumeRenderingAction()
         */
        void pauseRenderingAction();

        /**
         * Invoked for expensive modifications, ie while reparenting and ScreenMode change.
         * No lock is hold when invoked.<br>
         *
         * @see #pauseRenderingAction()
         */
        void resumeRenderingAction();
    }

    private LifecycleHook lifecycleHook = null;
    private RecursiveLock windowLock = new RecursiveLock();
    private long   windowHandle;
    private ScreenImpl screen;
    private boolean screenReferenced = false;
    private NativeWindow parentWindow;
    private long parentWindowHandle;

    protected AbstractGraphicsConfiguration config;
    protected Capabilities caps;
    protected boolean fullscreen, visible, hasFocus;
    protected int width, height, x, y;
    protected int nfs_width, nfs_height, nfs_x, nfs_y; // non fullscreen dimensions ..

    protected String title = "Newt Window";
    protected boolean undecorated = false;
    private boolean handleDestroyNotify = true;

    private final void destroyScreen() {
        if(null!=screen) {
            if(screenReferenced) {
                screen.removeReference();
                screenReferenced = false;
            }
            screen = null;
        }
    }
    private final void setScreen(ScreenImpl newScreen) {
        if(screenReferenced) {
            screenReferenced = false;
            screen.removeReference();
        }
        screen = newScreen;
    }

    private boolean createNative() {
        if(DEBUG_IMPLEMENTATION) {
            System.err.println("Window.createNative() START ("+getThreadName()+", "+this+")");
        }
        if( null != parentWindow && 
            NativeSurface.LOCK_SURFACE_NOT_READY >= parentWindow.lockSurface() ) {
                throw new NativeWindowException("Parent surface lock: not ready: "+parentWindow);
        }
        try {
            if(validateParentWindowHandle()) {
                if(!screenReferenced) {
                    screenReferenced = true;
                    screen.addReference();
                }
                createNativeImpl();
                setVisibleImpl(true, x, y, width, height);
                screen.addScreenModeListener(this);
            }
        } finally {
            if(null!=parentWindow) {
                parentWindow.unlockSurface();
            }
        }
        if(DEBUG_IMPLEMENTATION) {
            System.err.println("Window.createNative() END ("+getThreadName()+", "+this+")");
        }
        return 0 != windowHandle ;
    }

    private boolean validateParentWindowHandle() {
        if(null!=parentWindow) {
            parentWindowHandle = getNativeWindowHandle(parentWindow);
            return 0 != parentWindowHandle ;
        }
        return true;
    }

    private static long getNativeWindowHandle(NativeWindow nativeWindow) {
        long handle = 0;
        if(null!=nativeWindow) {
            boolean wasLocked = false;
            if( NativeSurface.LOCK_SURFACE_NOT_READY < nativeWindow.lockSurface() ) {
                wasLocked = true;
                try {
                    handle = nativeWindow.getWindowHandle();
                    if(0==handle) {
                        throw new NativeWindowException("Parent native window handle is NULL, after succesful locking: "+nativeWindow);
                    }
                } catch (NativeWindowException nwe) {
                    if(DEBUG_IMPLEMENTATION) {
                        System.err.println("Window.getNativeWindowHandle: not successful yet: "+nwe);
                    }
                } finally {
                    nativeWindow.unlockSurface();
                }
            }
            if(DEBUG_IMPLEMENTATION) {
                System.err.println("Window.getNativeWindowHandle: locked "+wasLocked+", "+nativeWindow);
            }
        }
        return handle;
    }


    //----------------------------------------------------------------------
    // NativeSurface: Native implementation
    //

    protected int lockSurfaceImpl() { return LOCK_SUCCESS; }

    protected void unlockSurfaceImpl() { }

    //----------------------------------------------------------------------
    // Window: Native implementation
    //

    /** 
     * The native implementation must set the native windowHandle.<br>
     *
     * The implementation should invoke the referenced java state callbacks
     * to notify this Java object of state changes.
     * 
     * @see #windowDestroyNotify()
     * @see #focusChanged(boolean)
     * @see #visibleChanged(boolean)
     * @see #sizeChanged(int,int)
     * @see #positionChanged(int,int)
     * @see #windowDestroyNotify()
     */
    protected abstract void createNativeImpl();

    protected abstract void closeNativeImpl();

    /** 
     * The native implementation must invoke {@link #focusChanged(boolean)}
     * to change the focus state, if <code>force == false</code>. 
     * This may happen asynchronous within {@link #TIMEOUT_NATIVEWINDOW}.
     * 
     * @param force if true, bypass {@link #focusChanged(boolean)} and force focus request
     */
    protected abstract void requestFocusImpl(boolean force);

    /** 
     * The native implementation must invoke {@link #visibleChanged(boolean)}
     * to change the visibility state. This may happen asynchronous within 
     * {@link #TIMEOUT_NATIVEWINDOW}.
     */
    protected abstract void setVisibleImpl(boolean visible, int x, int y, int width, int height);

    /**
     * The native implementation should invoke the referenced java state callbacks
     * to notify this Java object of state changes.
     * 
     * @param x -1 if no position change requested, otherwise greater than zero
     * @param y -1 if no position change requested, otherwise greater than zero
     * @param width -1 if no size change requested, otherwise greater than zero
     * @param height -1 if no size change requested, otherwise greater than zero
     * @param parentChange true if reparenting requested, otherwise false
     * @param fullScreenChange 0 if unchanged, -1 fullscreen off, 1 fullscreen on
     * @param decorationChange 0 if unchanged, -1 undecorated, 1 decorated
     *
     * @see #sizeChanged(int,int)
     * @see #positionChanged(int,int)
     */
    protected abstract boolean reconfigureWindowImpl(int x, int y, int width, int height, 
                                                     boolean parentChange, int fullScreenChange, int decorationChange);

    protected void setTitleImpl(String title) {}

    /**
     * Return screen coordinates of the given coordinates
     * or null, in which case a NativeWindow traversal shall being used
     * as demonstrated in {@link #getLocationOnScreen(javax.media.nativewindow.util.Point)}.
     *
     * @return if not null, the screen location of the given coordinates
     */
    protected abstract Point getLocationOnScreenImpl(int x, int y);

    //----------------------------------------------------------------------
    // NativeSurface
    //

    public final int lockSurface() {
        int res = LOCK_SURFACE_NOT_READY;

        windowLock.lock();

        AbstractGraphicsDevice adevice = screen.getDisplay().getGraphicsDevice();
        adevice.lock();
        try {
            res = lockSurfaceImpl();
        } finally {
            if(!isNativeValid()) {
                adevice.unlock();
                windowLock.unlock();
                res = LOCK_SURFACE_NOT_READY;
            }
        }

        return res;
    }

    public final void unlockSurface() {
        // may throw RuntimeException if not locked
        windowLock.validateLocked();

        try {
            unlockSurfaceImpl();
        } finally {
            screen.getDisplay().getGraphicsDevice().unlock();
        }
        windowLock.unlock();
    }

    public final boolean isSurfaceLockedByOtherThread() {
        return windowLock.isLockedByOtherThread();
    }

    public final boolean isSurfaceLocked() {
        return windowLock.isLocked();
    }

    public final Thread getSurfaceLockOwner() {
        return windowLock.getOwner();
    }

    public long getSurfaceHandle() {
        return windowHandle; // default: return window handle
    }

    public boolean surfaceSwap() {
        return false;
    }

    public AbstractGraphicsConfiguration getGraphicsConfiguration() {
        return config;
    }

    public final long getDisplayHandle() {
        return getScreen().getDisplay().getHandle();
    }

    public final int  getScreenIndex() {
        return getScreen().getIndex();
    }

    //----------------------------------------------------------------------
    // NativeWindow
    //

    public final void destroy() {
        destroy(false);
    }

    public final NativeWindow getParent() {
        return parentWindow;
    }

    public final long getWindowHandle() {
        return windowHandle;
    }

    public Point getLocationOnScreen(Point storage) {
        if(isNativeValid()) {
            Point d;
            windowLock.lock();
            try {
                d = getLocationOnScreenImpl(0, 0);
            } finally {
                windowLock.unlock();
            }
            if(null!=d) {
                if(null!=storage) {
                    storage.translate(d.getX(),d.getY());
                    return storage;
                }
                return d;
            }
            // fall through intended ..
        }

        if(null!=storage) {
            storage.translate(getX(),getY());
        } else {
            storage = new Point(getX(),getY());
        }
        if(null!=parentWindow) {
            // traverse through parent list ..
            parentWindow.getLocationOnScreen(storage);
        }
        return storage;
    }

    //----------------------------------------------------------------------
    // Window
    //

    public final boolean isNativeValid() {
        return null != getScreen() && 0 != getWindowHandle() ;
    }

    public final boolean isValid() {
        return null != getScreen() ;
    }

    public final Screen getScreen() {
        return screen;
    }

    class VisibleAction implements Runnable {
        boolean visible;
        boolean nativeWindowCreated;
        boolean madeVisible;

        public VisibleAction (boolean visible) {
            this.visible = visible;
            this.nativeWindowCreated = false;
            this.madeVisible = false;
        }

        public final boolean getNativeWindowCreated() { return nativeWindowCreated; }
        public final boolean getBecameVisible() { return madeVisible; }
        public final boolean getChanged() { return nativeWindowCreated || madeVisible; }

        public void run() {
            windowLock.lock();
            try {
                if(DEBUG_IMPLEMENTATION) {
                    String msg = new String("Window setVisible: START ("+getThreadName()+") "+x+"/"+y+" "+width+"x"+height+", fs "+fullscreen+", windowHandle "+toHexString(windowHandle)+", visible: "+WindowImpl.this.visible+" -> "+visible+", parentWindowHandle "+toHexString(WindowImpl.this.parentWindowHandle)+", parentWindow "+(null!=WindowImpl.this.parentWindow)/*+", "+this*/);
                    System.err.println(msg);
                }
                if(!visible && childWindows.size()>0) {
                  synchronized(childWindowsLock) {
                    for(int i = 0; i < childWindows.size(); i++ ) {
                        NativeWindow nw = (NativeWindow) childWindows.get(i);
                        if(nw instanceof WindowImpl) {
                            ((WindowImpl)nw).setVisible(false);
                        }
                    }
                  }
                }
                if(0==windowHandle && visible) {
                    if( 0<width*height ) {
                        nativeWindowCreated = createNative();
                        WindowImpl.this.waitForVisible(visible, true);
                        madeVisible = visible;
                    }
                } else if(WindowImpl.this.visible != visible) {
                    if(0 != windowHandle) {
                        setVisibleImpl(visible, x, y, width, height);
                        WindowImpl.this.waitForVisible(visible, true);
                        madeVisible = visible;
                    }
                }

                if(null!=lifecycleHook) {
                    lifecycleHook.setVisibleAction(visible, nativeWindowCreated);
                }

                if(0!=windowHandle && visible && childWindows.size()>0) {
                  synchronized(childWindowsLock) {
                    for(int i = 0; i < childWindows.size(); i++ ) {
                        NativeWindow nw = (NativeWindow) childWindows.get(i);
                        if(nw instanceof WindowImpl) {
                            ((WindowImpl)nw).setVisible(true);
                        }
                    }
                  }
                }
                if(DEBUG_IMPLEMENTATION) {
                    System.err.println("Window setVisible: END ("+getThreadName()+") "+x+"/"+y+" "+width+"x"+height+", fs "+fullscreen+", windowHandle "+toHexString(windowHandle)+", visible: "+WindowImpl.this.visible+", nativeWindowCreated: "+nativeWindowCreated+", madeVisible: "+madeVisible);
                }
            } finally {
                windowLock.unlock();
            }
        }
    }

    public void setVisible(boolean visible) {
        if(isValid()) {
            if( 0==windowHandle && visible && 0>=width*height ) {
                // fast-path: not realized yet, make visible, but zero size
                return;
            }
            VisibleAction visibleAction = new VisibleAction(visible);
            runOnEDTIfAvail(true, visibleAction);
            if( visibleAction.getChanged() ) {
                sendWindowEvent(WindowEvent.EVENT_WINDOW_RESIZED); // trigger a resize/relayout and repaint to listener
            }
        }
    }

    class SetSizeActionImpl implements Runnable {
        int visibleAction = 0; // 1 invisible, 2 visible (create)
        int width, height;

        public int getVisibleAction() { 
            return visibleAction;
        }
        public SetSizeActionImpl(int w, int h) {
            width = w;
            height = h;
        }
        public void run() {
            windowLock.lock();
            try {
                if ( !fullscreen && ( width != WindowImpl.this.width || WindowImpl.this.height != height ) ) {
                    if(DEBUG_IMPLEMENTATION) {
                        String msg = new String("Window setSize: START "+WindowImpl.this.width+"x"+WindowImpl.this.height+" -> "+width+"x"+height+", fs "+fullscreen+", windowHandle "+toHexString(windowHandle)+", visible "+visible);
                        System.err.println(msg);
                    }
                    if ( 0 != windowHandle && 0>=width*height && visible ) {
                        visibleAction=1; // invisible
                        WindowImpl.this.width = 0;
                        WindowImpl.this.height = 0;
                    } else if ( 0 == windowHandle && 0<width*height && visible ) {
                        visibleAction = 2; // visible (create)
                        WindowImpl.this.width = width;
                        WindowImpl.this.height = height;
                    } else if ( 0 != windowHandle ) {
                        // this width/height will be set by windowChanged, called by the native implementation
                        reconfigureWindowImpl(x, y, width, height, false, 0, 0);
                    } else {
                        WindowImpl.this.width = width;
                        WindowImpl.this.height = height;
                    }
                    if(DEBUG_IMPLEMENTATION) {
                        System.err.println("Window setSize: END "+WindowImpl.this.width+"x"+WindowImpl.this.height+", visibleAction "+visibleAction);
                    }
                }
            } finally {
                windowLock.unlock();
            }
        }
    }

    public void setSize(int width, int height) {
        if(isValid()) {
            SetSizeActionImpl setSizeAction = new SetSizeActionImpl(width, height);
            runOnEDTIfAvail(true, setSizeAction);
            switch(setSizeAction.getVisibleAction()) {
                case 1: setVisible(false); break;
                case 2: setVisible(true); break;
            }
        }
    }

    class DestroyAction implements Runnable {
        boolean unrecoverable;

        public DestroyAction(boolean unrecoverable) {
            this.unrecoverable = unrecoverable;
        }
        public void run() {
            windowLock.lock();
            try {
                if( !isValid() ) {
                    return; // nop
                }

                // Childs first ..
                synchronized(childWindowsLock) {
                  // avoid ConcurrentModificationException: parent -> child -> parent.removeChild(this)
                  ArrayList clonedChildWindows = (ArrayList) childWindows.clone(); 
                  while( clonedChildWindows.size() > 0 ) {
                    NativeWindow nw = (NativeWindow) clonedChildWindows.remove(0);
                    if(nw instanceof WindowImpl) {
                        ((WindowImpl)nw).sendWindowEvent(WindowEvent.EVENT_WINDOW_DESTROY_NOTIFY);
                        if(unrecoverable) {
                            ((WindowImpl)nw).destroy(unrecoverable);
                        }
                    } else {
                        nw.destroy();
                    }
                  }
                }

                if(null!=lifecycleHook) {
                    lifecycleHook.destroyActionInLock(unrecoverable);
                }

                // Now us ..
                if(unrecoverable) {
                    if(null!=parentWindow && parentWindow instanceof Window) {
                        ((Window)parentWindow).removeChild(WindowImpl.this);
                    }
                    synchronized(childWindowsLock) {
                        childWindows = new ArrayList();
                    }
                    synchronized(surfaceUpdatedListenersLock) {
                        surfaceUpdatedListeners = new ArrayList();
                    }
                    windowListeners = new ArrayList();
                    mouseListeners = new ArrayList();
                    keyListeners = new ArrayList();
                }
                if( null != screen && 0 != windowHandle ) {
                    screen.removeScreenModeListener(WindowImpl.this);
                    closeNativeImpl();
                }
                invalidate(unrecoverable);
                if(DEBUG_IMPLEMENTATION) {
                    System.err.println("Window.destroy(unrecoverable: "+unrecoverable+") END "+getThreadName()/*+", "+WindowImpl.this*/);
                }
            } finally {
                windowLock.unlock();
            }
        }
    }

    public void destroy(boolean unrecoverable) {
        if( isValid() ) {
            if(DEBUG_IMPLEMENTATION) {
                String msg = new String("Window.destroy(unrecoverable: "+unrecoverable+") START "+getThreadName()/*+", "+this*/);
                System.err.println(msg);
                //Exception ee = new Exception(msg);
                //ee.printStackTrace();
            }            
            if(null!=lifecycleHook) {
                lifecycleHook.destroyActionPreLock(unrecoverable);
            }
            DestroyAction destroyAction = new DestroyAction(unrecoverable);
            runOnEDTIfAvail(true, destroyAction);
        }
    }

    /**
     * <p>
     * render all native window information invalid,
     * as if the native window was destroyed.<br></p>
     * <p>
     * all other resources and states are kept intact,
     * ie listeners, parent handles and size, position etc.<br></p>
     *
     * @see #destroy()
     * @see #destroy(boolean)
     * @see #invalidate(boolean)
     */
    public final void invalidate() {
        invalidate(false);
    }

    /**
     * @param unrecoverable If true, all states, size, position, parent handles,
     * reference to it's Screen are reset.
     * Otherwise you can recreate the window, via <code>setVisible(true)</code>.
     * @see #invalidate()
     * @see #destroy()
     * @see #destroy(boolean)
     */
    protected void invalidate(boolean unrecoverable) {
        windowLock.lock();
        try {
            if(DEBUG_IMPLEMENTATION || DEBUG_WINDOW_EVENT) {
                String msg = new String("!!! Window Invalidate(unrecoverable: "+unrecoverable+") "+getThreadName());
                System.err.println(msg);
                // Exception e = new Exception(msg);
                // e.printStackTrace();
            }
            windowHandle = 0;
            visible = false;
            fullscreen = false;
            hasFocus = false;

            if(unrecoverable) {
                destroyScreen();
                parentWindowHandle = 0;
                parentWindow = null;
                caps = null;
                lifecycleHook = null;

                // Default position and dimension will be re-set immediately by user
                width  = 128;
                height = 128;
                x=0;
                y=0;
            }
        } finally {
            windowLock.unlock();
        }
    }

    class ReparentActionImpl implements Runnable, ReparentAction {
        NativeWindow newParentWindow;
        boolean forceDestroyCreate;
        int reparentAction;

        public ReparentActionImpl(NativeWindow newParentWindow, boolean forceDestroyCreate) {
            this.newParentWindow = newParentWindow;
            this.forceDestroyCreate = forceDestroyCreate;
            this.reparentAction = -1; // ensure it's set
        }

        public int getStrategy() {
            return reparentAction;
        }

        public void run() {
            boolean wasVisible;
            boolean displayChanged = false;

            // mirror pos/size so native change notification can get overwritten
            int x = WindowImpl.this.x;
            int y = WindowImpl.this.y;
            int width = WindowImpl.this.width;
            int height = WindowImpl.this.height;

            windowLock.lock();
            try {
                wasVisible = isVisible();

                Window newParentWindowNEWT = null;
                if(newParentWindow instanceof Window) {
                    newParentWindowNEWT = (Window) newParentWindow;
                }

                long newParentWindowHandle = 0 ;

                if(DEBUG_IMPLEMENTATION) {
                    System.err.println("Window.reparent: START ("+getThreadName()+") windowHandle "+toHexString(windowHandle)+" parentWindowHandle "+toHexString(parentWindowHandle)+", visible "+wasVisible+", old parentWindow: "+Display.hashCodeNullSafe(parentWindow)+", new parentWindow: "+Display.hashCodeNullSafe(newParentWindow)+", forceDestroyCreate "+forceDestroyCreate+", DEBUG_TEST_REPARENT_INCOMPATIBLE "+DEBUG_TEST_REPARENT_INCOMPATIBLE+" "+x+"/"+y+" "+width+"x"+height);
                }

                if(null!=newParentWindow) {
                    // reset position to 0/0 within parent space
                    x = 0;
                    y = 0;

                    // refit if size is bigger than parent
                    if( width > newParentWindow.getWidth() ) {
                        width = newParentWindow.getWidth();
                    }
                    if( height > newParentWindow.getHeight() ) {
                        height = newParentWindow.getHeight();
                    }

                    // Case: Child Window
                    newParentWindowHandle = getNativeWindowHandle(newParentWindow);
                    if(0 == newParentWindowHandle) {
                        // Case: Parent's native window not realized yet
                        if(null==newParentWindowNEWT) {
                            throw new NativeWindowException("Reparenting with non NEWT Window type only available after it's realized: "+newParentWindow);
                        }
                        // Destroy this window (handle screen + native) and use parent's Screen.
                        // It may be created properly when the parent is made visible.
                        destroy(false);
                        setScreen( (ScreenImpl) newParentWindowNEWT.getScreen() );
                        displayChanged = true;
                        reparentAction = ACTION_NATIVE_CREATION_PENDING;
                    } else if(newParentWindow != getParent()) {
                        // Case: Parent's native window realized and changed
                        if( !isNativeValid() ) {
                            // May create a new compatible Screen/Display and
                            // mark it for creation.
                            if(null!=newParentWindowNEWT) {
                                setScreen( (ScreenImpl) newParentWindowNEWT.getScreen() );
                            } else {
                                Screen newScreen = NewtFactory.createCompatibleScreen(newParentWindow, getScreen());
                                if( getScreen() != newScreen ) {
                                    // auto destroy on-the-fly created Screen/Display
                                    newScreen.setDestroyWhenUnused(true);
                                    setScreen( (ScreenImpl) newScreen );
                                    displayChanged = true;
                                }
                            }
                            if( 0<width*height ) {
                                reparentAction = ACTION_NATIVE_CREATION;
                            } else {
                                reparentAction = ACTION_NATIVE_CREATION_PENDING;
                            }
                        } else if ( DEBUG_TEST_REPARENT_INCOMPATIBLE || forceDestroyCreate ||
                                    !NewtFactory.isScreenCompatible(newParentWindow, getScreen()) ) {
                            // Destroy this window (handle screen + native) and
                            // may create a new compatible Screen/Display and
                            // mark it for creation.
                            destroy(false);
                            if(null!=newParentWindowNEWT) {
                                setScreen( (ScreenImpl) newParentWindowNEWT.getScreen() );
                            } else {
                                setScreen( (ScreenImpl) NewtFactory.createCompatibleScreen(newParentWindow, getScreen()) );
                                screen.setDestroyWhenUnused(true);
                            }
                            displayChanged = true;
                            reparentAction = ACTION_NATIVE_CREATION;
                        } else {
                            // Mark it for native reparenting
                            reparentAction = ACTION_NATIVE_REPARENTING;
                        }
                    } else {
                        // Case: Parent's native window realized and not changed
                        reparentAction = ACTION_UNCHANGED;
                    }
                } else {
                    if( null != parentWindow ) {
                        // child -> top
                        // put client to current parent+child position
                        Point p = getLocationOnScreen(null);
                        x = p.getX();
                        y = p.getY();
                    }

                    // Case: Top Window
                    if( 0 == getParentWindowHandle() ) {
                        // Already Top Window
                        reparentAction = ACTION_UNCHANGED;
                    } else if( !isNativeValid() || DEBUG_TEST_REPARENT_INCOMPATIBLE || forceDestroyCreate ) {
                        // Destroy this window (handle screen + native),
                        // keep Screen/Display and mark it for creation.
                        destroy(false);
                        if( 0<width*height ) {
                            reparentAction = ACTION_NATIVE_CREATION;
                        } else {
                            reparentAction = ACTION_NATIVE_CREATION_PENDING;
                        }
                    } else {
                        // Mark it for native reparenting
                        reparentAction = ACTION_NATIVE_REPARENTING;
                    }
                }
                parentWindowHandle = newParentWindowHandle;

                if ( ACTION_UNCHANGED > reparentAction ) {
                    throw new NativeWindowException("Internal Error: reparentAction not set");
                }

                if( ACTION_UNCHANGED == reparentAction ) {
                    if(DEBUG_IMPLEMENTATION) {
                        System.err.println("Window.reparent: NO CHANGE ("+getThreadName()+") windowHandle "+toHexString(windowHandle)+" new parentWindowHandle "+toHexString(newParentWindowHandle)+", visible "+wasVisible);
                    }
                    return;
                }

                if(DEBUG_IMPLEMENTATION) {
                    System.err.println("Window.reparent: ACTION ("+getThreadName()+") windowHandle "+toHexString(windowHandle)+" new parentWindowHandle "+toHexString(newParentWindowHandle)+", reparentAction "+reparentAction+", visible "+wasVisible);
                }

                // rearrange window tree
                if(null!=parentWindow && parentWindow instanceof Window) {
                    ((Window)parentWindow).removeChild(WindowImpl.this);
                }
                parentWindow = newParentWindow;
                if(parentWindow instanceof Window) {
                    ((Window)parentWindow).addChild(WindowImpl.this);
                }

                if( ACTION_NATIVE_CREATION_PENDING == reparentAction ) {
                    return;
                }

                if( ACTION_NATIVE_REPARENTING == reparentAction ) {
                    DisplayImpl display = (DisplayImpl) screen.getDisplay();
                    display.dispatchMessagesNative(); // status up2date
                    if(wasVisible) {
                        setVisibleImpl(false, x, y, width, height);
                        WindowImpl.this.waitForVisible(false, true);
                    }

                    // Lock parentWindow only during reparenting (attempt)
                    NativeWindow parentWindowLocked = null;
                    if( null != parentWindow ) {
                        parentWindowLocked = parentWindow;
                        if( NativeSurface.LOCK_SURFACE_NOT_READY >= parentWindowLocked.lockSurface() ) {
                            throw new NativeWindowException("Parent surface lock: not ready: "+parentWindow);
                        }
                    }
                    boolean ok = false;
                    try {
                        // write back mirrored values, to be able to detect satisfaction
                        WindowImpl.this.x = x;
                        WindowImpl.this.y = y;
                        WindowImpl.this.width = width;
                        WindowImpl.this.height = height;
                        ok = reconfigureWindowImpl(x, y, width, height, true, 0, isUndecorated()?-1:1);
                    } finally {
                        if(null!=parentWindowLocked) {
                            parentWindowLocked.unlockSurface();
                        }
                    }

                    // set visible again, and revalidate 'ok',
                    // since it has been experience that in some cases the reparented window gets hidden
                    if(ok) {
                        display.dispatchMessagesNative(); // status up2date
                        if(wasVisible) {
                            setVisibleImpl(true, x, y, width, height);
                            ok = WindowImpl.this.waitForVisible(true, false);
                            display.dispatchMessagesNative(); // status up2date
                            if( ok &&
                                ( WindowImpl.this.x != x ||
                                  WindowImpl.this.y != y ||
                                  WindowImpl.this.width != width ||
                                  WindowImpl.this.height != height ) )
                            {
                                if(DEBUG_IMPLEMENTATION) {
                                    System.err.println("Window.reparent (reconfig)");
                                }
                                // reset pos/size .. due to some native impl flakyness
                                reconfigureWindowImpl(x, y, width, height, false, 0, 0);
                                display.dispatchMessagesNative(); // status up2date
                            }
                        }
                    }

                    if(ok) {
                        if(wasVisible) {
                            requestFocusImpl(true);
                            display.dispatchMessagesNative(); // status up2date
                        }
                    } else {
                        // native reparent failed -> try creation
                        if(DEBUG_IMPLEMENTATION) {
                            System.err.println("Window.reparent: native reparenting failed ("+getThreadName()+") windowHandle "+toHexString(windowHandle)+" parentWindowHandle "+toHexString(parentWindowHandle)+" -> "+toHexString(newParentWindowHandle)+" - Trying recreation");
                        }
                        destroy(false);
                        reparentAction = ACTION_NATIVE_CREATION ;
                    }
                }

                // write back mirrored values, ensuring persitence
                // and not relying on native messaging
                WindowImpl.this.x = x;
                WindowImpl.this.y = y;
                WindowImpl.this.width = width;
                WindowImpl.this.height = height;

                if(DEBUG_IMPLEMENTATION) {
                    System.err.println("Window.reparentWindow: END ("+getThreadName()+") windowHandle "+toHexString(windowHandle)+", visible: "+visible+", parentWindowHandle "+toHexString(parentWindowHandle)+", parentWindow "+ Display.hashCodeNullSafe(parentWindow)+" "+x+"/"+y+" "+width+"x"+height);
                }
            } finally {
                windowLock.unlock();
            }

            if( ACTION_NATIVE_CREATION == reparentAction && wasVisible ) {
                // This may run on the Display/Screen connection,
                // hence a new EDT task
                runOnEDTIfAvail(true, reparentActionRecreate);
            }
        }
    }

    class ReparentActionRecreate implements Runnable {
        public void run() {
            windowLock.lock();
            try {
                visible = true;
                if(DEBUG_IMPLEMENTATION) {
                    System.err.println("Window.reparentWindow: ReparentActionRecreate ("+getThreadName()+") windowHandle "+toHexString(windowHandle)+", visible: "+visible+", parentWindowHandle "+toHexString(parentWindowHandle)+", parentWindow "+Display.hashCodeNullSafe(parentWindow));
                }
                setVisible(true); // native creation
            } finally {
                windowLock.unlock();
            }
        }
    }
    private ReparentActionRecreate reparentActionRecreate = new ReparentActionRecreate();

    public final int reparentWindow(NativeWindow newParent) {
        return reparentWindow(newParent, false);
    }

    public int reparentWindow(NativeWindow newParent, boolean forceDestroyCreate) {
        int reparentActionStrategy = ReparentAction.ACTION_INVALID;
        if(isValid()) {
            if(null!=lifecycleHook) {
                // pause animation
                lifecycleHook.pauseRenderingAction();
            }
            try {
                ReparentActionImpl reparentAction = new ReparentActionImpl(newParent, forceDestroyCreate);
                runOnEDTIfAvail(true, reparentAction);
                reparentActionStrategy = reparentAction.getStrategy();
            } finally {
                if(null!=lifecycleHook) {
                    // resume animation
                    lifecycleHook.resumeRenderingAction();
                }
            }
            sendWindowEvent(WindowEvent.EVENT_WINDOW_RESIZED); // trigger a resize/relayout and repaint to listener
        }
        return reparentActionStrategy;
    }

    public final Capabilities getChosenCapabilities() {
        return config.getNativeGraphicsConfiguration().getChosenCapabilities();
    }

    public final Capabilities getRequestedCapabilities() {
        return (Capabilities)caps.clone();
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        if (title == null) {
            title = "";
        }
        this.title = title;
        if(0 != getWindowHandle()) {
            setTitleImpl(title);
        }
    }

    class DecorationActionImpl implements Runnable {
        boolean undecorated;

        public DecorationActionImpl(boolean undecorated) {
            this.undecorated = undecorated;
        }

        public void run() {
            windowLock.lock();
            try {
                if(!fullscreen && isNativeValid() && WindowImpl.this.undecorated != undecorated) {
                    WindowImpl.this.undecorated = undecorated;
                    // mirror pos/size so native change notification can get overwritten
                    int x = WindowImpl.this.x;
                    int y = WindowImpl.this.y;
                    int width = WindowImpl.this.width;
                    int height = WindowImpl.this.height;

                    if( 0 != windowHandle ) {
                        DisplayImpl display = (DisplayImpl) screen.getDisplay();
                        display.dispatchMessagesNative(); // status up2date
                        boolean wasVisible = isVisible();
                        setVisibleImpl(false, x, y, width, height);
                        WindowImpl.this.waitForVisible(false, true);
                        display.dispatchMessagesNative(); // status up2date
                        reconfigureWindowImpl(x, y, width, height, false, 0, undecorated?-1:1);
                        display.dispatchMessagesNative(); // status up2date
                        if(wasVisible) {
                            setVisibleImpl(true, x, y, width, height);
                            WindowImpl.this.waitForVisible(true, true);
                            display.dispatchMessagesNative(); // status up2date
                            if( WindowImpl.this.x != x ||
                                WindowImpl.this.y != y ||
                                WindowImpl.this.width != width ||
                                WindowImpl.this.height != height ) 
                            {
                                // reset pos/size .. due to some native impl flakyness
                                reconfigureWindowImpl(x, y, width, height, false, 0, 0);
                                display.dispatchMessagesNative(); // status up2date
                            }
                            requestFocusImpl(true);
                            display.dispatchMessagesNative(); // status up2date
                        }
                    }
                }
            } finally {
                windowLock.unlock();
            }
        }
    }

    public void setUndecorated(boolean value) {
        if(isValid()) {
            DecorationActionImpl decorationAction = new DecorationActionImpl(value);
            runOnEDTIfAvail(true, decorationAction);
            sendWindowEvent(WindowEvent.EVENT_WINDOW_RESIZED); // trigger a resize/relayout and repaint to listener
        }
    }

    public boolean isUndecorated() {
        return 0 != parentWindowHandle || undecorated || fullscreen ;
    }

    public void requestFocus() {
        enqueueRequestFocus(true);
    }

    public boolean hasFocus() {
        return hasFocus;
    }

    public Insets getInsets() {
        return new Insets(0,0,0,0);
    }

    public final int getWidth() {
        return width;
    }

    public final int getHeight() {
        return height;
    }

    public final int getX() {
        return x;
    }

    public final int getY() {
        return y;
    }

    public final boolean isVisible() {
        return visible;
    }

    public final boolean isFullscreen() {
        return fullscreen;
    }


    //----------------------------------------------------------------------
    // Window
    //

    /**
     * If the implementation is capable of detecting a device change
     * return true and clear the status/reason of the change.
     */
    public boolean hasDeviceChanged() {
        return false;
    }

    public LifecycleHook getLifecycleHook() {
        return lifecycleHook;
    }

    public LifecycleHook setLifecycleHook(LifecycleHook hook) {
        LifecycleHook old = lifecycleHook;
        lifecycleHook = hook;
        return old;
    }

    /** If this Window actually wraps one from another toolkit such as
        the AWT, this will return a non-null value. */
    public Object getWrappedWindow() {
        return null;
    }

    /**
     * If set to true, the default value, this NEWT Window implementation will
     * handle the destruction (ie {@link #destroy()} call) within {@link #windowDestroyNotify()} implementation.<br>
     * If set to false, it's up to the caller/owner to handle destruction within {@link #windowDestroyNotify()}.
     */
    public void setHandleDestroyNotify(boolean b) {
        handleDestroyNotify = b;
    }

    //----------------------------------------------------------------------
    // WindowImpl
    //

    protected final long getParentWindowHandle() {
        return parentWindowHandle;
    }

    public String toString() {
        StringBuffer sb = new StringBuffer();

        sb.append(getClass().getName()+"[Config "+config+
                    "\n, "+screen+
                    "\n, ParentWindow "+parentWindow+
                    "\n, ParentWindowHandle "+toHexString(parentWindowHandle)+
                    "\n, WindowHandle "+toHexString(getWindowHandle())+
                    "\n, SurfaceHandle "+toHexString(getSurfaceHandle())+ " (lockedExt "+isSurfaceLockedByOtherThread()+")"+
                    "\n, Pos "+getX()+"/"+getY()+", size "+getWidth()+"x"+getHeight()+
                    "\n, Visible "+isVisible()+
                    "\n, Undecorated "+undecorated+
                    "\n, Fullscreen "+fullscreen+
                    "\n, WrappedWindow "+getWrappedWindow()+
                    "\n, ChildWindows "+childWindows.size());

        sb.append(", SurfaceUpdatedListeners num "+surfaceUpdatedListeners.size()+" [");
        for (int i = 0; i < surfaceUpdatedListeners.size(); i++ ) {
          sb.append(surfaceUpdatedListeners.get(i)+", ");
        }
        sb.append("], WindowListeners num "+windowListeners.size()+" [");
        for (int i = 0; i < windowListeners.size(); i++ ) {
          sb.append(windowListeners.get(i)+", ");
        }
        sb.append("], MouseListeners num "+mouseListeners.size()+" [");
        for (int i = 0; i < mouseListeners.size(); i++ ) {
          sb.append(mouseListeners.get(i)+", ");
        }
        sb.append("], KeyListeners num "+keyListeners.size()+" [");
        for (int i = 0; i < keyListeners.size(); i++ ) {
          sb.append(keyListeners.get(i)+", ");
        }
        sb.append("] ]");
        return sb.toString();
    }

    protected final void setWindowHandle(long handle) {
        windowHandle = handle;
    }

    public void runOnEDTIfAvail(boolean wait, final Runnable task) {
        Screen screen = getScreen();
        if(null==screen) {
            throw new RuntimeException("Null screen of inner class: "+this);
        }
        DisplayImpl d = (DisplayImpl) screen.getDisplay();
        d.runOnEDTIfAvail(wait, task);
    }

    class RequestFocusAction implements Runnable {
        public void run() {
            if(DEBUG_IMPLEMENTATION) {
                System.err.println("Window.RequestFocusAction: ("+getThreadName()+"): "+hasFocus+" -> true - windowHandle "+toHexString(windowHandle)+" parentWindowHandle "+toHexString(parentWindowHandle));
            }
            WindowImpl.this.requestFocusImpl(false);
        }
    }
    RequestFocusAction requestFocusAction = new RequestFocusAction();

    protected void enqueueRequestFocus(boolean wait) {
        runOnEDTIfAvail(wait, requestFocusAction);
    }

    /** 
     * May set to a {@link FocusRunnable}, {@link FocusRunnable#run()} before Newt requests the native focus.
     * This allows notifying a covered window toolkit like AWT that the focus is requested,
     * hence focus traversal can be made transparent.
     */
    public void setFocusAction(FocusRunnable focusAction) {
        this.focusAction = focusAction;
    }
    protected boolean focusAction() {
        if(DEBUG_IMPLEMENTATION) {
            System.err.println("Window.focusAction() START - "+getThreadName()+", focusAction: "+focusAction+" - windowHandle "+toHexString(getWindowHandle()));
        }
        boolean res;
        if(null!=focusAction) {
            res = focusAction.run();
        } else {
            res = false;
        }
        if(DEBUG_IMPLEMENTATION) {
            System.err.println("Window.focusAction() END - "+getThreadName()+", focusAction: "+focusAction+" - windowHandle "+toHexString(getWindowHandle())+", res: "+res);
        }
        return res;
    }
    protected FocusRunnable focusAction = null;

    class SetPositionActionImpl implements Runnable {
        int x, y;

        public SetPositionActionImpl(int x, int y) {
            this.x = x;
            this.y = y;
        }
        public void run() {
            windowLock.lock();
            try {
                if(DEBUG_IMPLEMENTATION) {
                    System.err.println("Window setPosition: "+WindowImpl.this.x+"/"+WindowImpl.this.y+" -> "+x+"/"+y+", fs "+fullscreen+", windowHandle "+toHexString(windowHandle));
                }
                if ( WindowImpl.this.x != x || WindowImpl.this.y != y ) {
                    if(!fullscreen) {
                        if(0!=windowHandle) {
                            // this.x/this.y will be set by windowChanged, called by the native implementation
                            reconfigureWindowImpl(x, y, -1, -1, false, 0, 0);
                        } else {
                            WindowImpl.this.x = x;
                            WindowImpl.this.y = y;
                        }
                    }
                }
            } finally {
                windowLock.unlock();
            }
        }
    }

    public void setPosition(int x, int y) {
        if(isValid()) {
            SetPositionActionImpl setPositionAction = new SetPositionActionImpl(x, y);
            runOnEDTIfAvail(true, setPositionAction);
        }
    }

    class FullScreenActionImpl implements Runnable {
        boolean fullscreen;

        public FullScreenActionImpl (boolean fullscreen) {
            this.fullscreen = fullscreen;
        }

        public void run() {
            windowLock.lock();
            try {
                if(isNativeValid() && WindowImpl.this.fullscreen != fullscreen) {
                    int x,y,w,h;
                    WindowImpl.this.fullscreen = fullscreen;
                    if(fullscreen) {
                        x = 0; y = 0;
                        w = screen.getWidth();
                        h = screen.getHeight();
                        nfs_width = width;
                        nfs_height = height;
                        nfs_x = x;
                        nfs_y = y;
                    } else {
                        x = nfs_x;
                        y = nfs_y;
                        w = nfs_width;
                        h = nfs_height;
                    }
                    if(DEBUG_IMPLEMENTATION || DEBUG_WINDOW_EVENT) {
                        System.err.println("Window fs: "+fullscreen+" "+x+"/"+y+" "+w+"x"+h+", "+isUndecorated()+", "+screen);
                    }

                    DisplayImpl display = (DisplayImpl) screen.getDisplay();
                    display.dispatchMessagesNative(); // status up2date
                    boolean wasVisible = isVisible();
                    setVisibleImpl(false, x, y, width, height);
                    WindowImpl.this.waitForVisible(false, true);
                    display.dispatchMessagesNative(); // status up2date

                    // write back mirrored values, to be able to detect satisfaction
                    WindowImpl.this.x = x;
                    WindowImpl.this.y = y;
                    WindowImpl.this.width = w;
                    WindowImpl.this.height = h;
                    reconfigureWindowImpl(x, y, w, h, getParentWindowHandle()!=0, fullscreen?1:-1, isUndecorated()?-1:1);
                    display.dispatchMessagesNative(); // status up2date

                    if(wasVisible) {
                        setVisibleImpl(true, x, y, width, height);
                        boolean ok = WindowImpl.this.waitForVisible(true, true, Screen.SCREEN_MODE_CHANGE_TIMEOUT);
                        display.dispatchMessagesNative(); // status up2date
                        if( ok &&
                            ( WindowImpl.this.x != x ||
                              WindowImpl.this.y != y ||
                              WindowImpl.this.width != w ||
                              WindowImpl.this.height != h ) )
                        {
                            if(DEBUG_IMPLEMENTATION || DEBUG_WINDOW_EVENT) {
                                System.err.println("Window fs (reconfig): "+x+"/"+y+" "+w+"x"+h+", "+screen);
                            }
                            // reset pos/size .. due to some native impl flakyness
                            reconfigureWindowImpl(x, y, width, height, false, 0, 0);
                            display.dispatchMessagesNative(); // status up2date
                        }
                        requestFocusImpl(true);
                        display.dispatchMessagesNative(); // status up2date
                        if(DEBUG_IMPLEMENTATION || DEBUG_WINDOW_EVENT) {
                            System.err.println("Window fs done");
                        }
                    }
                }
            } finally {
                windowLock.unlock();
            }
        }
    }

    public boolean setFullscreen(boolean fullscreen) {
        if(isValid()) {
            FullScreenActionImpl fullScreenAction = new FullScreenActionImpl(fullscreen);
            runOnEDTIfAvail(true, fullScreenAction);
            sendWindowEvent(WindowEvent.EVENT_WINDOW_RESIZED); // trigger a resize/relayout and repaint to listener
        }
        return this.fullscreen;
    }

    public void screenModeChangeNotify(ScreenMode sm) {
        if(DEBUG_IMPLEMENTATION || DEBUG_WINDOW_EVENT) {
            System.err.println("Window.screenModeChangeNotify: "+sm);
        }

        if(null!=lifecycleHook) {
            // pause animation
            lifecycleHook.pauseRenderingAction();
        }
    }

    public void screenModeChanged(ScreenMode sm, boolean success) {
        if(DEBUG_IMPLEMENTATION || DEBUG_WINDOW_EVENT) {
            System.err.println("Window.screenModeChanged: "+sm+", success: "+success);
        }

        if(success) {
            DimensionReadOnly screenSize = sm.getMonitorMode().getSurfaceSize().getResolution();
            if ( getHeight() > screenSize.getHeight()  ||
                 getWidth() > screenSize.getWidth() ) {
                setSize(screenSize.getWidth(), screenSize.getHeight());
            }
        }

        if(null!=lifecycleHook) {
            // resume animation
            lifecycleHook.resumeRenderingAction();
            sendWindowEvent(WindowEvent.EVENT_WINDOW_RESIZED); // trigger a resize/relayout and repaint to listener
        }
    }

    //----------------------------------------------------------------------
    // Child Window Management
    // 

    private ArrayList childWindows = new ArrayList();
    private Object childWindowsLock = new Object();

    public final void removeChild(NativeWindow win) {
        synchronized(childWindowsLock) {
            childWindows.remove(win);
        }
    }

    public final void addChild(NativeWindow win) {
        if (win == null) {
            return;
        }
        synchronized(childWindowsLock) {
            childWindows.add(win);
        }
    }

    //----------------------------------------------------------------------
    // Generic Event Support
    //
    private void doEvent(boolean enqueue, boolean wait, com.jogamp.newt.event.NEWTEvent event) {
        boolean done = false;

        if(!enqueue) {
            done = consumeEvent(event);
            wait = done; // don't wait if event can't be consumed now
        }

        if(!done) {
            enqueueEvent(wait, event);
        }
    }

    public void enqueueEvent(boolean wait, com.jogamp.newt.event.NEWTEvent event) {
        if(isValid()) {
            ((DisplayImpl)getScreen().getDisplay()).enqueueEvent(wait, event);
        }
    }

    public boolean consumeEvent(NEWTEvent e) {
        switch(e.getEventType()) {
            // special repaint treatment
            case WindowEvent.EVENT_WINDOW_REPAINT:
                // queue repaint event in case surface is locked, ie in operation
                if( isSurfaceLocked() ) {
                    // make sure only one repaint event is queued
                    if(!repaintQueued) {
                        repaintQueued=true;
                        if(DEBUG_IMPLEMENTATION) {
                            System.err.println("Window.consumeEvent: queued "+e);
                            // Exception ee = new Exception("Window.windowRepaint: "+e);
                            // ee.printStackTrace();
                        }
                        return false;
                    }
                    return true;
                }
                repaintQueued=false; // no repaint event queued
                break;

            // common treatment
            case WindowEvent.EVENT_WINDOW_RESIZED:
                // queue event in case surface is locked, ie in operation
                if( isSurfaceLocked() ) {
                    if(DEBUG_IMPLEMENTATION) {
                        System.err.println("Window.consumeEvent: queued "+e);
                        // Exception ee = new Exception("Window.windowRepaint: "+e);
                        // ee.printStackTrace();
                    }
                    return false;
                }
                break;
            default:
                break;
        }
        if(e instanceof WindowEvent) {
            consumeWindowEvent((WindowEvent)e);
        } else if(e instanceof KeyEvent) {
            consumeKeyEvent((KeyEvent)e);
        } else if(e instanceof MouseEvent) {
            consumeMouseEvent((MouseEvent)e);
        } else {
            throw new NativeWindowException("Unexpected NEWTEvent type " + e);
        }
        return true;
    }
    protected boolean repaintQueued = false;

    //
    // SurfaceUpdatedListener Support
    //

    private ArrayList surfaceUpdatedListeners = new ArrayList();
    private Object surfaceUpdatedListenersLock = new Object();

    public void addSurfaceUpdatedListener(SurfaceUpdatedListener l) {
        addSurfaceUpdatedListener(-1, l);
    }

    public void addSurfaceUpdatedListener(int index, SurfaceUpdatedListener l) 
        throws IndexOutOfBoundsException
    {
        if(l == null) {
            return;
        }
        synchronized(surfaceUpdatedListenersLock) {
            if(0>index) { 
                index = surfaceUpdatedListeners.size(); 
            }
            surfaceUpdatedListeners.add(index, l);
        }
    }

    public void removeSurfaceUpdatedListener(SurfaceUpdatedListener l) {
        if (l == null) {
            return;
        }
        synchronized(surfaceUpdatedListenersLock) {
            surfaceUpdatedListeners.remove(l);
        }
    }

    public void removeAllSurfaceUpdatedListener() {
        synchronized(surfaceUpdatedListenersLock) {
            surfaceUpdatedListeners = new ArrayList();
        }
    }

    public SurfaceUpdatedListener getSurfaceUpdatedListener(int index) {
        synchronized(surfaceUpdatedListenersLock) {
            if(0>index) { 
                index = surfaceUpdatedListeners.size()-1; 
            }
            return (SurfaceUpdatedListener) surfaceUpdatedListeners.get(index);
        }
    }

    public SurfaceUpdatedListener[] getSurfaceUpdatedListeners() {
        synchronized(surfaceUpdatedListenersLock) {
            return (SurfaceUpdatedListener[]) surfaceUpdatedListeners.toArray();
        }
    }

    public void surfaceUpdated(Object updater, NativeSurface ns, long when) {
        synchronized(surfaceUpdatedListenersLock) {
          for(int i = 0; i < surfaceUpdatedListeners.size(); i++ ) {
            SurfaceUpdatedListener l = (SurfaceUpdatedListener) surfaceUpdatedListeners.get(i);
            l.surfaceUpdated(updater, ns, when);
          }
        }
    }

    //
    // MouseListener/Event Support
    //
    private ArrayList mouseListeners = new ArrayList();
    private int  mouseButtonPressed = 0; // current pressed mouse button number
    private long lastMousePressed = 0; // last time when a mouse button was pressed
    private int  lastMouseClickCount = 0; // last mouse button click count

    public void sendMouseEvent(int eventType, int modifiers,
                               int x, int y, int button, int rotation) {
        doMouseEvent(false, false, eventType, modifiers, x, y, button, rotation);
    }
    public void enqueueMouseEvent(boolean wait, int eventType, int modifiers,
                                  int x, int y, int button, int rotation) {
        doMouseEvent(true, wait, eventType, modifiers, x, y, button, rotation);
    }
    private void doMouseEvent(boolean enqueue, boolean wait, int eventType, int modifiers,
                              int x, int y, int button, int rotation) {
        if(x<0||y<0||x>=width||y>=height) {
            return; // .. invalid ..
        }
        if(DEBUG_MOUSE_EVENT) {
            System.err.println("doMouseEvent: enqueue"+enqueue+", wait "+wait+", "+MouseEvent.getEventTypeString(eventType)+
                               ", mod "+modifiers+", pos "+x+"/"+y+", button "+button);
        }
        if(button<0||button>MouseEvent.BUTTON_NUMBER) {
            throw new NativeWindowException("Invalid mouse button number" + button);
        }
        long when = System.currentTimeMillis();
        MouseEvent eClicked = null;
        MouseEvent e = null;

        if(MouseEvent.EVENT_MOUSE_PRESSED==eventType) {
            if(when-lastMousePressed<MouseEvent.getClickTimeout()) {
                lastMouseClickCount++;
            } else {
                lastMouseClickCount=1;
            }
            lastMousePressed=when;
            mouseButtonPressed=button;
            e = new MouseEvent(eventType, this, when,
                               modifiers, x, y, lastMouseClickCount, button, 0);
        } else if(MouseEvent.EVENT_MOUSE_RELEASED==eventType) {
            e = new MouseEvent(eventType, this, when,
                               modifiers, x, y, lastMouseClickCount, button, 0);
            if(when-lastMousePressed<MouseEvent.getClickTimeout()) {
                eClicked = new MouseEvent(MouseEvent.EVENT_MOUSE_CLICKED, this, when,
                                          modifiers, x, y, lastMouseClickCount, button, 0);
            } else {
                lastMouseClickCount=0;
                lastMousePressed=0;
            }
            mouseButtonPressed=0;
        } else if(MouseEvent.EVENT_MOUSE_MOVED==eventType) {
            if (mouseButtonPressed>0) {
                e = new MouseEvent(MouseEvent.EVENT_MOUSE_DRAGGED, this, when,
                                   modifiers, x, y, 1, mouseButtonPressed, 0);
            } else {
                e = new MouseEvent(eventType, this, when,
                                   modifiers, x, y, 0, button, 0);
            }
        } else if(MouseEvent.EVENT_MOUSE_WHEEL_MOVED==eventType) {
            e = new MouseEvent(eventType, this, when, modifiers, x, y, 0, button, rotation);
        } else {
            e = new MouseEvent(eventType, this, when, modifiers, x, y, 0, button, 0);
        }
        doEvent(enqueue, wait, e);
        if(null!=eClicked) {
            if(DEBUG_MOUSE_EVENT) {
                System.err.println("doMouseEvent: synthesized MOUSE_CLICKED event");
            }
            doEvent(enqueue, wait, eClicked);
        }
    }


    public void addMouseListener(MouseListener l) {
        addMouseListener(-1, l);
    }

    public void addMouseListener(int index, MouseListener l) {
        if(l == null) {
            return;
        }
        ArrayList clonedListeners = (ArrayList) mouseListeners.clone();
        if(0>index) { 
            index = clonedListeners.size(); 
        }
        clonedListeners.add(index, l);
        mouseListeners = clonedListeners;
    }

    public void removeMouseListener(MouseListener l) {
        if (l == null) {
            return;
        }
        ArrayList clonedListeners = (ArrayList) mouseListeners.clone();
        clonedListeners.remove(l);
        mouseListeners = clonedListeners;
    }

    public MouseListener getMouseListener(int index) {
        ArrayList clonedListeners = (ArrayList) mouseListeners.clone();
        if(0>index) { 
            index = clonedListeners.size()-1; 
        }
        return (MouseListener) clonedListeners.get(index);
    }

    public MouseListener[] getMouseListeners() {
        return (MouseListener[]) mouseListeners.toArray();
    }

    protected void consumeMouseEvent(MouseEvent e) {
        if(DEBUG_MOUSE_EVENT) {
            System.err.println("consumeMouseEvent: event:         "+e);
        }

        for(int i = 0; i < mouseListeners.size(); i++ ) {
            MouseListener l = (MouseListener) mouseListeners.get(i);
            switch(e.getEventType()) {
                case MouseEvent.EVENT_MOUSE_CLICKED:
                    l.mouseClicked(e);
                    break;
                case MouseEvent.EVENT_MOUSE_ENTERED:
                    l.mouseEntered(e);
                    break;
                case MouseEvent.EVENT_MOUSE_EXITED:
                    l.mouseExited(e);
                    break;
                case MouseEvent.EVENT_MOUSE_PRESSED:
                    l.mousePressed(e);
                    break;
                case MouseEvent.EVENT_MOUSE_RELEASED:
                    l.mouseReleased(e);
                    break;
                case MouseEvent.EVENT_MOUSE_MOVED:
                    l.mouseMoved(e);
                    break;
                case MouseEvent.EVENT_MOUSE_DRAGGED:
                    l.mouseDragged(e);
                    break;
                case MouseEvent.EVENT_MOUSE_WHEEL_MOVED:
                    l.mouseWheelMoved(e);
                    break;
                default:
                    throw new NativeWindowException("Unexpected mouse event type " + e.getEventType());
            }
        }
    }

    //
    // KeyListener/Event Support
    //

    public void sendKeyEvent(int eventType, int modifiers, int keyCode, char keyChar) {
        consumeKeyEvent(new KeyEvent(eventType, this, System.currentTimeMillis(), modifiers, keyCode, keyChar) );
    }

    public void enqueueKeyEvent(boolean wait, int eventType, int modifiers, int keyCode, char keyChar) {
        enqueueEvent(wait, new KeyEvent(eventType, this, System.currentTimeMillis(), modifiers, keyCode, keyChar) );
    }

    public void addKeyListener(KeyListener l) {
        addKeyListener(-1, l);
    }

    public void addKeyListener(int index, KeyListener l) {
        if(l == null) {
            return;
        }
        ArrayList clonedListeners = (ArrayList) keyListeners.clone();
        if(0>index) { 
            index = clonedListeners.size();
        }
        clonedListeners.add(index, l);
        keyListeners = clonedListeners;
    }

    public void removeKeyListener(KeyListener l) {
        if (l == null) {
            return;
        }
        ArrayList clonedListeners = (ArrayList) keyListeners.clone();
        clonedListeners.remove(l);
        keyListeners = clonedListeners;
    }

    public KeyListener getKeyListener(int index) {
        ArrayList clonedListeners = (ArrayList) keyListeners.clone();
        if(0>index) { 
            index = clonedListeners.size()-1;
        }
        return (KeyListener) clonedListeners.get(index);
    }

    public KeyListener[] getKeyListeners() {
        return (KeyListener[]) keyListeners.toArray();
    }

    private ArrayList keyListeners = new ArrayList();

    protected void consumeKeyEvent(KeyEvent e) {
        if(DEBUG_KEY_EVENT) {
            System.err.println("consumeKeyEvent: "+e);
        }
        for(int i = 0; i < keyListeners.size(); i++ ) {
            KeyListener l = (KeyListener) keyListeners.get(i);
            switch(e.getEventType()) {
                case KeyEvent.EVENT_KEY_PRESSED:
                    l.keyPressed(e);
                    break;
                case KeyEvent.EVENT_KEY_RELEASED:
                    l.keyReleased(e);
                    break;
                case KeyEvent.EVENT_KEY_TYPED:
                    l.keyTyped(e);
                    break;
                default:
                    throw new NativeWindowException("Unexpected key event type " + e.getEventType());
            }
        }
    }

    //
    // WindowListener/Event Support
    //
    public void sendWindowEvent(int eventType) {
        consumeWindowEvent( new WindowEvent(eventType, this, System.currentTimeMillis()) );
    }

    public void enqueueWindowEvent(boolean wait, int eventType) {
        enqueueEvent( wait, new WindowEvent(eventType, this, System.currentTimeMillis()) );
    }

    private ArrayList windowListeners = new ArrayList();

    public void addWindowListener(WindowListener l) {
        addWindowListener(-1, l);
    }

    public void addWindowListener(int index, WindowListener l) 
        throws IndexOutOfBoundsException
    {
        if(l == null) {
            return;
        }
        ArrayList clonedListeners = (ArrayList) windowListeners.clone();
        if(0>index) { 
            index = clonedListeners.size(); 
        }
        clonedListeners.add(index, l);
        windowListeners = clonedListeners;
    }

    public final void removeWindowListener(WindowListener l) {
        if (l == null) {
            return;
        }
        ArrayList clonedListeners = (ArrayList) windowListeners.clone();
        clonedListeners.remove(l);
        windowListeners = clonedListeners;
    }

    public WindowListener getWindowListener(int index) {
        ArrayList clonedListeners = (ArrayList) windowListeners.clone();
        if(0>index) { 
            index = clonedListeners.size()-1; 
        }
        return (WindowListener) clonedListeners.get(index);
    }

    public WindowListener[] getWindowListeners() {
        return (WindowListener[]) windowListeners.toArray();
    }

    protected void consumeWindowEvent(WindowEvent e) {
        if(DEBUG_WINDOW_EVENT) {
            System.err.println("consumeWindowEvent: "+e+", visible "+isVisible()+" "+getX()+"/"+getY()+" "+getWidth()+"x"+getHeight());
        }
        for(int i = 0; i < windowListeners.size(); i++ ) {
            WindowListener l = (WindowListener) windowListeners.get(i);
            switch(e.getEventType()) {
                case WindowEvent.EVENT_WINDOW_RESIZED:
                    l.windowResized(e);
                    break;
                case WindowEvent.EVENT_WINDOW_MOVED:
                    l.windowMoved(e);
                    break;
                case WindowEvent.EVENT_WINDOW_DESTROY_NOTIFY:
                    l.windowDestroyNotify(e);
                    break;
                case WindowEvent.EVENT_WINDOW_GAINED_FOCUS:
                    l.windowGainedFocus(e);
                    break;
                case WindowEvent.EVENT_WINDOW_LOST_FOCUS:
                    l.windowLostFocus(e);
                    break;
                case WindowEvent.EVENT_WINDOW_REPAINT:
                    l.windowRepaint((WindowUpdateEvent)e);
                    break;
                default:
                    throw 
                        new NativeWindowException("Unexpected window event type "
                                                  + e.getEventType());
            }
        }
    }

    /**
     * @param focusGained
     */
    protected void focusChanged(boolean focusGained) {
        if(DEBUG_IMPLEMENTATION) {
            System.err.println("Window.focusChanged: ("+getThreadName()+"): "+this.hasFocus+" -> "+focusGained+" - windowHandle "+toHexString(windowHandle)+" parentWindowHandle "+toHexString(parentWindowHandle));
        }
        hasFocus = focusGained;
        if (focusGained) {
            sendWindowEvent(WindowEvent.EVENT_WINDOW_GAINED_FOCUS);
        } else {
            sendWindowEvent(WindowEvent.EVENT_WINDOW_LOST_FOCUS);
        }
    }

    protected void visibleChanged(boolean visible) {
        if(DEBUG_IMPLEMENTATION) {
            System.err.println("Window.visibleChanged ("+getThreadName()+"): "+this.visible+" -> "+visible+" - windowHandle "+toHexString(windowHandle)+" parentWindowHandle "+toHexString(parentWindowHandle));
        }
        this.visible = visible ;
    }

    private boolean waitForVisible(boolean visible, boolean failFast) {
        return waitForVisible(visible, failFast, TIMEOUT_NATIVEWINDOW);
    }

    private boolean waitForVisible(boolean visible, boolean failFast, long timeOut) {
        DisplayImpl display = (DisplayImpl) screen.getDisplay();
        for(long sleep = timeOut; 0<sleep && this.visible != visible; sleep-=10 ) {
            display.dispatchMessagesNative(); // status up2date
            try {
                Thread.sleep(10);
            } catch (InterruptedException ie) {}
            sleep -=10;
        }
        if(this.visible != visible) {
            if(failFast) {
                throw new NativeWindowException("Visibility not reached as requested within "+timeOut+"ms : requested "+visible+", is "+this.visible);
            } else if (DEBUG_IMPLEMENTATION) {
                System.err.println("******* Visibility not reached as requested within "+timeOut+"ms : requested "+visible+", is "+this.visible);
            }
        }
        return this.visible == visible;
    }

    protected void sizeChanged(int newWidth, int newHeight, boolean force) {
        if(force || width != newWidth || height != newHeight) {
            if(DEBUG_IMPLEMENTATION) {
                System.err.println("Window.sizeChanged: ("+getThreadName()+"): force "+force+", "+width+"x"+height+" -> "+newWidth+"x"+newHeight+" - windowHandle "+toHexString(windowHandle)+" parentWindowHandle "+toHexString(parentWindowHandle));
            }
            width = newWidth;
            height = newHeight;
            if(isNativeValid()) {
                sendWindowEvent(WindowEvent.EVENT_WINDOW_RESIZED);
            }
        }
    }

    protected void positionChanged(int newX, int newY) {
        if( 0==parentWindowHandle && ( x != newX || y != newY ) ) {
            if(DEBUG_IMPLEMENTATION) {
                System.err.println("Window.positionChanged: ("+getThreadName()+"): "+x+"/"+y+" -> "+newX+"/"+newY+" - windowHandle "+toHexString(windowHandle)+" parentWindowHandle "+toHexString(parentWindowHandle));
            }
            x = newX;
            y = newY;
            sendWindowEvent(WindowEvent.EVENT_WINDOW_MOVED);
        }
    }

    protected void windowDestroyNotify() {
        if(DEBUG_IMPLEMENTATION) {
            System.err.println("Window.windowDestroyNotify START "+getThreadName());
        }

        enqueueWindowEvent(false, WindowEvent.EVENT_WINDOW_DESTROY_NOTIFY);

        if(handleDestroyNotify && isValid()) {
            destroy();
        }

        if(DEBUG_IMPLEMENTATION) {
            System.err.println("Window.windowDestroyeNotify END "+getThreadName());
        }
    }

    protected void windowDestroyed() {
        if(DEBUG_IMPLEMENTATION) {
            System.err.println("Window.windowDestroyed "+getThreadName());
        }
        invalidate();
    }

    public void windowRepaint(int x, int y, int width, int height) {
        if(DEBUG_IMPLEMENTATION) {
            System.err.println("Window.windowRepaint "+getThreadName()+" - "+x+"/"+y+" "+width+"x"+height);
            // Exception ee = new Exception("Window.windowRepaint: "+" - "+x+"/"+y+" "+width+"x"+height);
            // ee.printStackTrace();
        }

        if(isNativeValid()) {
            if(0>width) {
                width=this.width;
            }
            if(0>height) {
                height=this.height;
            }

            NEWTEvent e = new WindowUpdateEvent(WindowEvent.EVENT_WINDOW_REPAINT, this, System.currentTimeMillis(),
                                                new Rectangle(x, y, width, height));
            doEvent(false, false, e);
        }
    }

    protected int getWindowLockRecursionCount() {
        return windowLock.getRecursionCount();
    }

    //
    // Reflection helper ..
    //

    private static Class[] getCustomConstructorArgumentTypes(Class windowClass) {
        Class[] argTypes = null;
        try {
            Method m = windowClass.getDeclaredMethod("getCustomConstructorArgumentTypes", new Class[] {});
            argTypes = (Class[]) m.invoke(null, null);
        } catch (Throwable t) {}
        return argTypes;
    }

    private static int verifyConstructorArgumentTypes(Class[] types, Object[] args) {
        if(types.length != args.length) {
            return -1;
        }
        for(int i=0; i<args.length; i++) {
            if(!types[i].isInstance(args[i])) {
                return i;
            }
        }
        return args.length;
    }

    private static String getArgsStrList(Object[] args) {
        StringBuffer sb = new StringBuffer();
        for(int i=0; i<args.length; i++) {
            sb.append(args[i].getClass());
            if(i<args.length) {
                sb.append(", ");
            }
        }
        return sb.toString();
    }

    private static String getTypeStrList(Class[] types) {
        StringBuffer sb = new StringBuffer();
        for(int i=0; i<types.length; i++) {
            sb.append(types[i]);
            if(i<types.length) {
                sb.append(", ");
            }
        }
        return sb.toString();
    }

    protected final void shouldNotCallThis() {
        throw new NativeWindowException("Should not call this");
    }
    
    public static String getThreadName() {
        return Display.getThreadName();
    }

    public static String toHexString(int hex) {
        return Display.toHexString(hex);
    }

    public static String toHexString(long hex) {
        return Display.toHexString(hex);
    }
}
