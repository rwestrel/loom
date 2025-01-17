/*
 * Copyright (c) 2017, 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package sun.nio.ch;

import java.io.IOException;
import static sun.nio.ch.KQueue.*;

/**
 * Poller implementation based on the kqueue facility.
 */
class KQueuePoller extends Poller {
    private static final int MAX_EVENTS_TO_POLL = 512;

    private final int kqfd;
    private final int filter;
    private final long address;

    private final int fd0;
    private final int fd1;
    private final Object lock = new Object();
    private boolean wakeupTriggered;

    KQueuePoller(boolean read) throws IOException {
        super(read);
        this.kqfd = KQueue.create();
        this.filter = (read) ? EVFILT_READ : EVFILT_WRITE;
        this.address = KQueue.allocatePollArray(MAX_EVENTS_TO_POLL);

        long fds = IOUtil.makePipe(false);
        this.fd0 = (int) (fds >>> 32);
        this.fd1 = (int) fds;
        KQueue.register(kqfd, fd0, EVFILT_READ, EV_ADD);
    }

    @Override
    void implRegister(int fdVal) throws IOException {
        int err = KQueue.register(kqfd, fdVal, filter, (EV_ADD|EV_ONESHOT));
        if (err != 0)
            throw new IOException("kevent failed: " + err);
    }

    @Override
    void implDeregister(int fdVal) {
        KQueue.register(kqfd, fdVal, filter, EV_DELETE);
    }

    @Override
    void wakeup() {
        synchronized (lock) {
            if (!wakeupTriggered) {
                try {
                    IOUtil.write1(fd1, (byte)0);
                } catch (IOException ioe) {
                    throw new InternalError(ioe);
                }
                wakeupTriggered = true;
            }
        }
    }

    @Override
    void poll(int timeout) throws IOException {
        int n = KQueue.poll(kqfd, address, MAX_EVENTS_TO_POLL, timeout);
        int i = 0;
        while (i < n) {
            long keventAddress = KQueue.getEvent(address, i);
            int fdVal = KQueue.getDescriptor(keventAddress);
            if (fdVal == fd0) {
                synchronized (lock) {
                    IOUtil.drain(fd0);
                    wakeupTriggered = false;
                }
            } else {
                polled(fdVal);
            }
            i++;
        }
    }
}
