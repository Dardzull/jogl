/**
 * Copyright 2010 JogAmp Community. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 * 
 *    1. Redistributions of source code must retain the above copyright notice, this list of
 *       conditions and the following disclaimer.
 * 
 *    2. Redistributions in binary form must reproduce the above copyright notice, this list
 *       of conditions and the following disclaimer in the documentation and/or other materials
 *       provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED BY JogAmp Community ``AS IS'' AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL JogAmp Community OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 * 
 * The views and conclusions contained in the software and documentation are those of the
 * authors and should not be interpreted as representing official policies, either expressed
 * or implied, of JogAmp Community.
 */
 
package com.jogamp.opengl.test.junit.newt.event;

import org.eclipse.swt.SWT ;
import org.eclipse.swt.layout.FillLayout ;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display ;
import org.eclipse.swt.widgets.Shell ;

import javax.media.opengl.GLCapabilities ;
import javax.media.opengl.GLProfile ;

import org.junit.AfterClass ;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.BeforeClass ;

import com.jogamp.nativewindow.swt.SWTAccessor;
import com.jogamp.newt.opengl.GLWindow ;
import com.jogamp.newt.swt.NewtCanvasSWT ;
import com.jogamp.opengl.test.junit.jogl.demos.es2.RedSquareES2;
import com.jogamp.opengl.test.junit.util.AWTRobotUtil;

/**
 * Test whether or not event modifiers preserved by NEWT when
 * the canvas is a NewtCanvasSWT.
 */

public class TestNewtEventModifiersNewtCanvasSWT extends BaseNewtEventModifiers {

    private static Display _display = null;
    private static Shell _shell = null;
    private static Composite _composite = null;
    private static GLWindow _glWindow ;

    ////////////////////////////////////////////////////////////////////////////
    
    protected static void eventDispatch2xImpl() {
        eventDispatchImpl();
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) { }
        eventDispatchImpl();
    }
    
    protected static void eventDispatchImpl() {
        if( !_display.isDisposed() ) {
            if( !_display.readAndDispatch() ) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) { }
            }
        }                
    }
    
    @Override
    protected void eventDispatch() {
        eventDispatchImpl();
    }
    
    ////////////////////////////////////////////////////////////////////////////

    @BeforeClass
    public static void beforeClass() throws Exception {

        SWTAccessor.invoke(true, new Runnable() {
            public void run() {  
                _display = new Display();
            }});
        Assert.assertNotNull( _display );
        
        _display.syncExec(new Runnable() {
            public void run() {        
                _shell = new Shell( _display );
                Assert.assertNotNull( _shell );
                _shell.setText( "Event Modifier Test NewtCanvasSWT" ) ;
                _shell.setLayout( new FillLayout() );
                _composite = new Composite( _shell, SWT.NONE );
                _composite.setLayout( new FillLayout() );
                Assert.assertNotNull( _composite );
            }});

        {
            GLCapabilities caps = new GLCapabilities( GLProfile.get( GLProfile.GL2ES2 ) ) ;
            _glWindow = GLWindow.create( caps ) ;
            _glWindow.addGLEventListener( new RedSquareES2() ) ;

            NewtCanvasSWT.create( _composite, SWT.NO_BACKGROUND, _glWindow ) ;
        }
        
        _display.syncExec( new Runnable() {
           public void run() {
              _shell.setBounds( TEST_FRAME_X, TEST_FRAME_Y, TEST_FRAME_WIDTH, TEST_FRAME_HEIGHT ) ;
              _shell.open();
           }
        });
        
        // no AWT idling, may deadlock on OSX!
        Assert.assertNotNull(_robot);
        _robot.setAutoWaitForIdle( false ) ;
        
        // no waiting for results ..
        AWTRobotUtil.requestFocus(null, _glWindow, false); // programmatic
        eventDispatch2xImpl();
        AWTRobotUtil.requestFocus(_robot, _glWindow, INITIAL_MOUSE_X, INITIAL_MOUSE_Y);
        eventDispatch2xImpl();
        
        _glWindow.addMouseListener( _testMouseListener ) ;
    }

    ////////////////////////////////////////////////////////////////////////////

    @AfterClass
    public static void afterClass() throws Exception {
        baseAfterClass();
        
        _glWindow.destroy() ;

        try {
            _display.syncExec(new Runnable() {
               public void run() {
                _composite.dispose();
                _shell.dispose();
               }});
            
            if(!_display.isDisposed()) {
                SWTAccessor.invoke(true, new Runnable() {
                    public void run() {        
                        _display.dispose();
                    }});
            }            
        }
        catch( Throwable throwable ) {
            throwable.printStackTrace();
            Assume.assumeNoException( throwable );
        }
    }

    ////////////////////////////////////////////////////////////////////////////

    public static void main(String args[]) throws Exception {
        String testName = TestNewtEventModifiersNewtCanvasSWT.class.getName() ;
        org.junit.runner.JUnitCore.main( testName ) ;
    }

    ////////////////////////////////////////////////////////////////////////////
}
