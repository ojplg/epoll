package org.jetlang.epoll;

import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.nio.channels.DatagramChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

public class EPoll implements Executor {

    private static final int EVENT_SIZE = 8 + 4 + 4 + 8;

    private static final Unsafe unsafe;

    static {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            unsafe = (Unsafe) f.get(null);
        } catch (Exception failed) {
            throw new ExceptionInInitializerError(failed);
        }
        System.loadLibrary("jetlang-epoll");
    }

    private final Object lock = new Object();
    private final long ptrAddress;
    private final long readBufferAddress;
    private final long eventArrayAddress;
    private final Thread thread;
    private boolean running = true;
    private ArrayList<Runnable> pending = new ArrayList<>();
    private final ArrayList<State> unused = new ArrayList<>();
    private final ArrayList<State> fds = new ArrayList<State>();
    private final Map<Integer, State> stateMap = new HashMap<>();
    private final AtomicBoolean started = new AtomicBoolean();

    private interface EventHandler {
        void onEvent(Unsafe unsafe, long readBufferAddress);
    }

    private static class State {

        public int fd;
        public final int idx;
        public EventHandler handler;
        private boolean hasNativeStructure;
        private long eventAddress;

        public State(int idx) {
            this.idx = idx;
        }

        public void cleanupNativeResources(Unsafe unsafe) {
            if(hasNativeStructure) {
                hasNativeStructure = false;
                unsafe.freeMemory(eventAddress);
            }
        }

        public void setNativeStructureAddress(long ptr) {
            this.eventAddress = ptr;
            hasNativeStructure = true;
        }

        public void init(int fd, DatagramReader reader) {
            this.fd = fd;
            this.handler = new EventHandler() {
                @Override
                public void onEvent(Unsafe unsafe, long readBufferAddress) {

                }
            };
        }
    }

    public EPoll(String threadName, int maxSelectedEvents, int maxDatagramsPerRead, int readBufferBytes) {
        this.ptrAddress = init(maxSelectedEvents, maxDatagramsPerRead, readBufferBytes);
        this.readBufferAddress = getReadBufferAddress(ptrAddress);
        this.eventArrayAddress = getEventArrayAddress(ptrAddress);
        System.out.println("eventArrayAddress = " + eventArrayAddress);

        Runnable eventLoop = () -> {
            while (running) {
                int events = select(ptrAddress, -1);
                System.out.println("events = " + events);
                for (int i = 0; i < events; i++) {
                    long structAddress = eventArrayAddress + EVENT_SIZE * i;
                    int idx = unsafe.getInt(structAddress + 4);
                    System.out.println("idx = " + idx);
                    fds.get(idx).handler.onEvent(unsafe, readBufferAddress);
                }
            }
            cleanUpNativeResources();
        };
        this.thread = new Thread(eventLoop, threadName);
        State interrupt = claimState();
        interrupt.handler = new EventHandler() {
            ArrayList<Runnable> swap = new ArrayList<>();

            @Override
            public void onEvent(Unsafe unsafe, long readBufferAddress) {
                synchronized (lock) {
                    ArrayList<Runnable> tmp = pending;
                    pending = swap;
                    swap = tmp;
                    clearInterrupt(ptrAddress);
                }
                for (int i = 0, size = swap.size(); i < size; i++) {
                    runEvent(swap.get(i));
                }
            }
        };
    }

    private void cleanUpNativeResources() {
        List<Integer> allFds = new ArrayList<>(fds.size());
        for (State fd : fds) {
            allFds.add(fd.idx);
        }
        for (Integer allFd : allFds) {
            remove(allFd);
        }
        freeNativeMemory(ptrAddress);
    }

    public void start() {
        if (started.compareAndSet(false, true)) {
            thread.start();
        }
    }

    public void close() {
        if (started.compareAndSet(false, true)) {
            cleanUpNativeResources();
        } else {
            execute(() -> {
                running = false;
            });
        }
    }


    protected void runEvent(Runnable runnable) {
        runnable.run();
    }

    private static native int select(long ptrAddress, int timeout);

    private static native long getEventArrayAddress(long ptrAddress);

    private static native long getReadBufferAddress(long ptrAddress);

    private static native long init(int maxSelectedEvents, int maxDatagramsPerRead, int readBufferBytes);

    private static native void freeNativeMemory(long ptrAddress);

    private static native void interrupt(long ptrAddress);

    private static native void clearInterrupt(long ptrAddress);

    private static native long ctl(long ptrAddress, int op, int eventTypes, int fd, int idx);

    public Runnable register(DatagramChannel channel, DatagramReader reader) {
        final int fd = FdUtils.getFd(channel);
        execute(() -> {
            State e = claimState();
            e.init(fd, reader);;
            addFd(EventTypes.EPOLLIN.ordinal(), fd, e);
            stateMap.put(fd, e);
        });
        return () -> {
            execute(() -> {
                remove(fd);
            });
        };
    }

    private void remove(int fd) {
        State st = stateMap.remove(fd);
        if (st != null) {
            ctl(ptrAddress, Ops.Del.ordinal(), 0, fd, st.idx);
            unused.add(st);
            st.cleanupNativeResources(unsafe);
        }
    }

    private void addFd(int eventTypes, int fd, State st) {
        st.setNativeStructureAddress(ctl(ptrAddress, Ops.Add.ordinal(), eventTypes, fd, st.idx));
    }

    private State claimState() {
        if (!unused.isEmpty()) {
            return unused.remove(unused.size() - 1);
        } else {
            State st = new State(fds.size());
            fds.add(st);
            return st;
        }
    }

    @Override
    public void execute(Runnable runnable) {
        synchronized (lock) {
            if (running) {
                pending.add(runnable);
                if (pending.size() == 1) {
                    interrupt(ptrAddress);
                }
            }
        }
    }

    enum Ops {
        Add, Mod, Del
    }

    enum EventTypes {
        EPOLLIN, EPOLLOUT
    }
}
