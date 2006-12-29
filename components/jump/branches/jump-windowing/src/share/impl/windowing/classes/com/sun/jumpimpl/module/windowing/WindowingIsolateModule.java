/*
 * %W% %E%
 *
 * Copyright  1990-2006 Sun Microsystems, Inc. All Rights Reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License version
 * 2 only, as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License version 2 for more details (a copy is
 * included at /legal/license.txt).
 *
 * You should have received a copy of the GNU General Public License
 * version 2 along with this work; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA
 *
 * Please contact Sun Microsystems, Inc., 4150 Network Circle, Santa
 * Clara, CA 95054 or visit www.sun.com if you need additional
 * information or have any questions.
 */

package com.sun.jumpimpl.module.windowing;

import com.sun.jump.message.JUMPMessage;
import com.sun.jump.message.JUMPMessageSender;
import com.sun.jump.message.JUMPMessageHandler;
import com.sun.jump.message.JUMPMessageDispatcher;
import com.sun.jump.message.JUMPMessageDispatcherTypeException;
import com.sun.jump.isolate.jvmprocess.JUMPIsolateProcess;
import com.sun.jump.command.JUMPExecutiveWindowRequest;
import com.sun.jump.command.JUMPIsolateWindowRequest;
import com.sun.jump.command.JUMPResponse;

import com.sun.jumpimpl.process.RequestSenderHelper;

import com.sun.me.gci.windowsystem.GCIDisplay;
import com.sun.me.gci.windowsystem.GCIScreenWidget;
import com.sun.me.gci.windowsystem.GCIDisplayListener;
import com.sun.me.gci.windowsystem.GCIGraphicsEnvironment;
import com.sun.me.gci.windowsystem.event.GCIFocusEvent;
import com.sun.me.gci.windowsystem.event.GCIFocusEventListener;

import java.awt.GraphicsEnvironment;

import java.util.Vector;


public class WindowingIsolateModule implements JUMPMessageHandler {

    private Vector              windows;
    private RequestSenderHelper requestSender;
    private ListenerImpl        listener;
    private int                 isolateId;
    private JUMPMessageSender   executive;
    private boolean             focusEventsSupported;

    private class ListenerImpl implements GCIDisplayListener, GCIFocusEventListener {

        private GCIScreenWidget selfContained;

        void
        setSelfContained(GCIScreenWidget selfContained) {
            this.selfContained = selfContained;
        }

        public void
        screenWidgetCreated(GCIDisplay source, GCIScreenWidget widget) {
            windows.add(widget);

            // FIXME: should wait for appropriate moment to send this message
            postRequest(
                widget, JUMPIsolateWindowRequest.ID_REQUEST_FOREGROUND);
        }

        public boolean
        focusEventReceived(GCIFocusEvent event) {
            // FIXME: how to figure of if it is a background or foreground notification?
            return true;
        }
    }

    private void
    postRequest(GCIScreenWidget widget, String requestId) {
        int winId = -1;
        synchronized(windows) {
            for(int i = 0, size = windows.size(); i != size; ++i) {
                if(windows.get(i) == widget) {
                    winId = i;
                    break;
                }
            }
        }

        if(winId != -1) {
            requestSender.sendRequestAsync(
                executive,
                new JUMPIsolateWindowRequest(
                    requestId, winId, isolateId));
        }

    }

    public WindowingIsolateModule(JUMPIsolateProcess host) {
        windows         = new Vector();
        requestSender   = new RequestSenderHelper(host);
        listener        = new ListenerImpl();
        isolateId       = host.getIsolateId();
        executive       = host.getExecutiveProcess();

        try {
            JUMPMessageDispatcher md = host.getMessageDispatcher();
            md.registerHandler(JUMPExecutiveWindowRequest.MESSAGE_TYPE, this);
        } catch (JUMPMessageDispatcherTypeException dte) {
            dte.printStackTrace();
            throw new IllegalStateException();
        }

        // kick start GCI native library loading
        GraphicsEnvironment.getLocalGraphicsEnvironment();

        // register listener with all available displays
        GCIGraphicsEnvironment gciEnv = GCIGraphicsEnvironment.getInstance();
        for(int i = 0, count = gciEnv.getNumDisplays(); i != count; ++i) {
            gciEnv.getDisplay(i).addListener(listener);
        }

        focusEventsSupported = gciEnv.getEventManager().supportsFocusEvents();
        if(focusEventsSupported) {
            // FIXME: install listener
        }
    }

    public void
    handleMessage(JUMPMessage message) {
        if(JUMPExecutiveWindowRequest.MESSAGE_TYPE.equals(message.getType())) {
            JUMPExecutiveWindowRequest msg =
                (JUMPExecutiveWindowRequest)
                    JUMPExecutiveWindowRequest.fromMessage(message);

            int winId = msg.getWindowId();
            GCIScreenWidget widget = null;
            synchronized(windows) {
                if(winId < windows.size()) {
                    widget = (GCIScreenWidget)windows.elementAt(winId);
                }
            }

            if(widget == null) {
                return;
            }

            try {
                listener.setSelfContained(widget);
                if(JUMPExecutiveWindowRequest.ID_FOREGROUND.equals(
                    msg.getCommandId())) {

                    GCIGraphicsEnvironment.getInstance(
                        ).getEventManager().startEventLoop();
                    widget.suspendRendering(false);
                }
                else if(JUMPExecutiveWindowRequest.ID_BACKGROUND.equals(
                    msg.getCommandId())) {

                    GCIGraphicsEnvironment.getInstance(
                        ).getEventManager().stopEventLoop();
                    widget.suspendRendering(true);
                }
            }
            finally {
                listener.setSelfContained(null);
            }
        }
    }
}
