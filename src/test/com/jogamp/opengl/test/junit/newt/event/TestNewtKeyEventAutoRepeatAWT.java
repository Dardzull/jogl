/**
 * Copyright 2012 JogAmp Community. All rights reserved.
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

import org.junit.After;
import org.junit.Assert;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.Before;

import java.awt.AWTException;
import java.awt.BorderLayout;
import java.awt.Robot;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.EventObject;
import java.util.List;

import javax.media.opengl.GLCapabilities;
import javax.media.opengl.GLEventListener;
import javax.swing.JFrame;

import java.io.IOException;

import org.junit.BeforeClass;
import org.junit.Test;

import com.jogamp.newt.awt.NewtCanvasAWT;
import com.jogamp.newt.event.InputEvent;
import com.jogamp.newt.event.KeyEvent;
import com.jogamp.newt.opengl.GLWindow;
import com.jogamp.opengl.util.Animator;
import com.jogamp.opengl.test.junit.jogl.demos.es2.RedSquareES2;

import com.jogamp.opengl.test.junit.util.*;

/**
 * Testing key event order incl. auto-repeat (Bug 601)
 * 
 * <p>
 * Note Event order:
 * <ol>
 *   <li>{@link #EVENT_KEY_PRESSED}</li>
 *   <li>{@link #EVENT_KEY_RELEASED}</li>
 *   <li>{@link #EVENT_KEY_TYPED}</li>
 * </ol>
 * </p>
 * <p>
 * Auto-Repeat shall behave as follow:
 * <pre>
    D = pressed, U = released, T = typed
    0 = normal, 1 = auto-repeat

    D(0), [ U(1), T(1), D(1), U(1) T(1) ..], U(0) T(0)
 * </pre>
 *
 * The idea is if you mask out auto-repeat in your event listener
 * you just get one long pressed key D/U/T triple.
 */
public class TestNewtKeyEventAutoRepeatAWT extends UITestCase {
    static int width, height;
    static long durationPerTest = 100;
    static long awtWaitTimeout = 1000;

    static GLCapabilities glCaps;

    @BeforeClass
    public static void initClass() {
        width = 640;
        height = 480;
        glCaps = new GLCapabilities(null);
    }

    @AfterClass
    public static void release() {
    }
    
    @Before
    public void initTest() {        
    }

    @After
    public void releaseTest() {        
    }
    
    @Test
    public void test01NEWT() throws AWTException, InterruptedException, InvocationTargetException {
        GLWindow glWindow = GLWindow.create(glCaps);
        glWindow.setSize(width, height);
        glWindow.setVisible(true);
        
        testImpl(glWindow);
        
        glWindow.destroy();
    }
        
    @Test
    public void test02NewtCanvasAWT() throws AWTException, InterruptedException, InvocationTargetException {
        GLWindow glWindow = GLWindow.create(glCaps);
        
        // Wrap the window in a canvas.
        final NewtCanvasAWT newtCanvasAWT = new NewtCanvasAWT(glWindow);
        
        // Add the canvas to a frame, and make it all visible.
        final JFrame frame1 = new JFrame("Swing AWT Parent Frame: "+ glWindow.getTitle());
        frame1.getContentPane().add(newtCanvasAWT, BorderLayout.CENTER);
        frame1.setSize(width, height);
        javax.swing.SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                frame1.setVisible(true);
            } } );
        
        Assert.assertEquals(true,  AWTRobotUtil.waitForVisible(frame1, true));
        
        testImpl(glWindow);
        
        try {
            javax.swing.SwingUtilities.invokeAndWait(new Runnable() {
                public void run() {
                    frame1.setVisible(false);
                    frame1.dispose();
                }});
        } catch( Throwable throwable ) {
            throwable.printStackTrace();
            Assume.assumeNoException( throwable );
        }        
        glWindow.destroy();
    }
    
    static void testKeyEventAutoRepeat(Robot robot, NEWTKeyAdapter keyAdapter, int loops, int pressDurationMS) {
        System.err.println("KEY Event Auto-Repeat Test: "+loops);
        EventObject[][] first = new EventObject[loops][3];
        EventObject[][] last = new EventObject[loops][3];
        
        keyAdapter.reset();
        int firstIdx = 0;
        for(int i=0; i<loops; i++) {
            System.err.println("+++ KEY Event Auto-Repeat START Input Loop: "+i);
            AWTRobotUtil.keyPress(0, robot, true, java.awt.event.KeyEvent.VK_A, pressDurationMS);
            robot.waitForIdle();
            AWTRobotUtil.keyPress(0, robot, false, java.awt.event.KeyEvent.VK_A, 500); // 1s .. no AR anymore
            robot.waitForIdle();            
            final int minCodeCount = firstIdx + 3;
            final int desiredCodeCount = firstIdx + 6;
            for(int j=0; j < 10 && keyAdapter.getQueueSize() < desiredCodeCount; j++) { // wait until events are collected
                robot.delay(100);
            }
            Assert.assertTrue("AR Test didn't collect enough key events: required min "+minCodeCount+", received "+(keyAdapter.getQueueSize()-firstIdx)+", "+keyAdapter.getQueued(), 
                              keyAdapter.getQueueSize() >= minCodeCount );
            final List<EventObject> keyEvents = keyAdapter.getQueued();
            first[i][0] = (KeyEvent) keyEvents.get(firstIdx+0);
            first[i][1] = (KeyEvent) keyEvents.get(firstIdx+1);
            first[i][2] = (KeyEvent) keyEvents.get(firstIdx+2);
            firstIdx = keyEvents.size() - 3;
            last[i][0] = (KeyEvent) keyEvents.get(firstIdx+0);
            last[i][1] = (KeyEvent) keyEvents.get(firstIdx+1);
            last[i][2] = (KeyEvent) keyEvents.get(firstIdx+2);
            System.err.println("+++ KEY Event Auto-Repeat END   Input Loop: "+i);
            
            // add a pair of normal press/release in between auto-repeat!
            firstIdx = keyEvents.size();
            AWTRobotUtil.keyPress(0, robot, true, java.awt.event.KeyEvent.VK_B, 10);
            robot.waitForIdle();
            AWTRobotUtil.keyPress(0, robot, false, java.awt.event.KeyEvent.VK_B, 250);
            robot.waitForIdle();
            for(int j=0; j < 10 && keyAdapter.getQueueSize() < firstIdx+3; j++) { // wait until events are collected
                robot.delay(100);
            }
            firstIdx = keyEvents.size();
        }
        // dumpKeyEvents(keyEvents);
        final List<EventObject> keyEvents = keyAdapter.getQueued();
        NEWTKeyUtil.validateKeyEventOrder(keyEvents);
        
        final boolean hasAR = 0 < keyAdapter.getKeyPressedCount(true) ;
        
        {
            final int expTotal = keyEvents.size();
            final int expAR = hasAR ? expTotal - 3 * 2 * loops : 0; // per loop: 3 for non AR events and 3 for non AR 'B'
            NEWTKeyUtil.validateKeyAdapterStats(keyAdapter, expTotal, expAR);
        }
        
        if( !hasAR ) {
            System.err.println("No AUTO-REPEAT triggered by AWT Robot .. aborting test analysis");
            return;
        }
        
        for(int i=0; i<loops; i++) {
            System.err.println("Auto-Repeat Loop "+i+" - Head:");
            NEWTKeyUtil.dumpKeyEvents(Arrays.asList(first[i]));
            System.err.println("Auto-Repeat Loop "+i+" - Tail:");
            NEWTKeyUtil.dumpKeyEvents(Arrays.asList(last[i]));
        }
        for(int i=0; i<loops; i++) {
            KeyEvent e = (KeyEvent) first[i][0];
            Assert.assertTrue("1st Shall be A, but is "+e, KeyEvent.VK_A == e.getKeyCode() );
            Assert.assertTrue("1st Shall be PRESSED, but is "+e, KeyEvent.EVENT_KEY_PRESSED == e.getEventType() );
            Assert.assertTrue("1st Shall not be AR, but is "+e, 0 == ( InputEvent.AUTOREPEAT_MASK & e.getModifiers() ) );
            
            e = (KeyEvent) first[i][1];
            Assert.assertTrue("2nd Shall be A, but is "+e, KeyEvent.VK_A == e.getKeyCode() );
            Assert.assertTrue("2nd Shall be RELEASED, but is "+e, KeyEvent.EVENT_KEY_RELEASED == e.getEventType() );
            Assert.assertTrue("2nd Shall be AR, but is "+e, 0 != ( InputEvent.AUTOREPEAT_MASK & e.getModifiers() ) );
            
            e = (KeyEvent) first[i][2];
            Assert.assertTrue("3rd Shall be A, but is "+e, KeyEvent.VK_A == e.getKeyCode() );
            Assert.assertTrue("3rd Shall be TYPED, but is "+e, KeyEvent.EVENT_KEY_TYPED == e.getEventType() );
            Assert.assertTrue("3rd Shall be AR, but is "+e, 0 != ( InputEvent.AUTOREPEAT_MASK & e.getModifiers() ) );
            
            e = (KeyEvent) last[i][0];
            Assert.assertTrue("last-2 Shall be A, but is "+e, KeyEvent.VK_A == e.getKeyCode() );
            Assert.assertTrue("last-2 Shall be PRESSED, but is "+e, KeyEvent.EVENT_KEY_PRESSED == e.getEventType() );
            Assert.assertTrue("last-2 Shall be AR, but is "+e, 0 != ( InputEvent.AUTOREPEAT_MASK & e.getModifiers() ) );
            
            e = (KeyEvent) last[i][1];
            Assert.assertTrue("last-1 Shall be A, but is "+e, KeyEvent.VK_A == e.getKeyCode() );
            Assert.assertTrue("last-1 Shall be RELEASED, but is "+e, KeyEvent.EVENT_KEY_RELEASED == e.getEventType() );
            Assert.assertTrue("last-1 Shall not be AR, but is "+e, 0 == ( InputEvent.AUTOREPEAT_MASK & e.getModifiers() ) );
            
            e = (KeyEvent) last[i][2];
            Assert.assertTrue("last-0 Shall be A, but is "+e, KeyEvent.VK_A == e.getKeyCode() );
            Assert.assertTrue("last-0 Shall be TYPED, but is "+e, KeyEvent.EVENT_KEY_TYPED == e.getEventType() );
            Assert.assertTrue("last-0 Shall not be AR, but is "+e, 0 == ( InputEvent.AUTOREPEAT_MASK & e.getModifiers() ) );
        }
    }
    
    void testImpl(GLWindow glWindow) throws AWTException, InterruptedException, InvocationTargetException {
        final Robot robot = new Robot();
        robot.setAutoWaitForIdle(true);

        GLEventListener demo1 = new RedSquareES2();
        glWindow.addGLEventListener(demo1);

        NEWTKeyAdapter glWindow1KA = new NEWTKeyAdapter("GLWindow1");
        glWindow1KA.setVerbose(false);
        glWindow.addKeyListener(glWindow1KA);

        Assert.assertEquals(true,  AWTRobotUtil.waitForRealized(glWindow, true));        

        // Continuous animation ..
        Animator animator = new Animator(glWindow);
        animator.start();

        Thread.sleep(durationPerTest); // manual testing
        
        AWTRobotUtil.assertRequestFocusAndWait(null, glWindow, glWindow, null, null);  // programmatic
        AWTRobotUtil.requestFocus(robot, glWindow, false); // within unit framework, prev. tests (TestFocus02SwingAWTRobot) 'confuses' Windows keyboard input
        glWindow1KA.reset();

        // 
        // Test the key event order w/ auto-repeat
        //
        final int origAutoDelay = robot.getAutoDelay();
        robot.setAutoDelay(10);
        try {
            testKeyEventAutoRepeat(robot, glWindow1KA, 3, 1000);
        } finally {        
            robot.setAutoDelay(origAutoDelay);
        }
        
        // Remove listeners to avoid logging during dispose/destroy.
        glWindow.removeKeyListener(glWindow1KA);

        // Shutdown the test.
        animator.stop();
    }

    static int atoi(String a) {
        int i=0;
        try {
            i = Integer.parseInt(a);
        } catch (Exception ex) { ex.printStackTrace(); }
        return i;
    }

    public static void main(String args[]) throws IOException {
        for(int i=0; i<args.length; i++) {
            if(args[i].equals("-time")) {
                durationPerTest = atoi(args[++i]);
            }
        }
        /**
        BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in));
        System.err.println("Press enter to continue");
        System.err.println(stdin.readLine()); 
        */
        System.out.println("durationPerTest: "+durationPerTest);
        String tstname = TestNewtKeyEventAutoRepeatAWT.class.getName();
        org.junit.runner.JUnitCore.main(tstname);
    }


}
