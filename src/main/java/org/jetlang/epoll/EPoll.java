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

    private static final int EVENT_SIZE;

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
        EVENT_SIZE = getEpollEventSize();
    }

    private final Object lock = new Object();
    private final long ptrAddress;
    private final long[] udpReadBuffers;
    private final long eventArrayAddress;
    private final Thread thread;
    private boolean running = true;
    private ArrayList<Runnable> pending = new ArrayList<>();
    private final ArrayList<State> unused = new ArrayList<>();
    private final ArrayList<State> fds = new ArrayList<State>();
    private final Map<Integer, State> stateMap = new HashMap<>();
    private final AtomicBoolean started = new AtomicBoolean();
    private final Controls controls = new Controls();

    private interface EventHandler {
        EventResult onEvent(Controls c, Unsafe unsafe, long[] readBufferAddress);

        void onRemove();
    }

    private class Controls {

        public int receive(int fd) {
            return recvmmsg(ptrAddress, fd);
        }
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
                public EventResult onEvent(Controls c, Unsafe unsafe, long[] readBufferAddress) {
                    int numRecv = c.receive(fd);
//                    if(numRecv != 1) {
//                        System.out.println("numRecv = " + numRecv);
//                    }
                    for(int i = 0; i < numRecv; i++){
                        EventResult r = reader.onRead(unsafe, readBufferAddress[i]);
                        if(r == EventResult.Remove){
                            return r;
                        }
                    }
                    return EventResult.Continue;
                }

                @Override
                public void onRemove() {
                    reader.onRemove();
                }
            };
        }
    }

    public EPoll(String threadName, int maxSelectedEvents, int maxDatagramsPerRead, int readBufferBytes) {
        this.ptrAddress = init(maxSelectedEvents, maxDatagramsPerRead, readBufferBytes);
        this.udpReadBuffers = new long[maxDatagramsPerRead];
        for(int i = 0; i < maxDatagramsPerRead; i++){
            this.udpReadBuffers[i] = getReadBufferAddress(ptrAddress, i);
            System.out.println("readBuffer i = " + i + " " + udpReadBuffers[i]);
        }
        this.eventArrayAddress = getEventArrayAddress(ptrAddress);
        System.out.println("eventArrayAddress = " + eventArrayAddress);

        Runnable eventLoop = () -> {
            while (running) {
                int events = select(ptrAddress, -1);
                for (int i = 0; i < events; i++) {
                    long structAddress = eventArrayAddress + EVENT_SIZE * i;
                    int idx = unsafe.getInt(structAddress + 4);
                    State state = fds.get(idx);
                    EventResult result = state.handler.onEvent(controls, unsafe, udpReadBuffers);
                    if(result == EventResult.Remove){
                        remove(state.fd);
                    }
                }
            }
            cleanUpNativeResources();
        };
        this.thread = new Thread(eventLoop, threadName);
        State interrupt = claimState();
        interrupt.handler = new EventHandler() {
            ArrayList<Runnable> swap = new ArrayList<>();

            @Override
            public EventResult onEvent(Controls c, Unsafe unsafe, long[] readBufferAddress) {
                synchronized (lock) {
                    ArrayList<Runnable> tmp = pending;
                    pending = swap;
                    swap = tmp;
                    clearInterrupt(ptrAddress);
                }
                for (int i = 0, size = swap.size(); i < size; i++) {
                    runEvent(swap.get(i));
                }
                swap.clear();
                return null;
            }

            @Override
            public void onRemove() {

            }
        };
    }

    private void cleanUpNativeResources() {
        List<Integer> allFds = new ArrayList<>(fds.size());
        for (State fd : fds) {
            allFds.add(fd.fd);
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

    private static native long getReadBufferAddress(long ptrAddress, int idx);

    private static native int getEpollEventSize();

    private static native long init(int maxSelectedEvents, int maxDatagramsPerRead, int readBufferBytes);

    private static native void freeNativeMemory(long ptrAddress);

    private static native void interrupt(long ptrAddress);

    private static native void clearInterrupt(long ptrAddress);

    private static native long ctl(long ptrAddress, int op, int eventTypes, int fd, int idx);

    private static native int recvmmsg(long ptrAddress, int fd);

    public Runnable register(DatagramChannel channel, DatagramReader reader) {
        final int fd = FdUtils.getFd(channel);
        execute(() -> {
            State e = claimState();
            e.init(fd, reader);
            addFd(EventTypes.EPOLLIN.value, fd, e);
            stateMap.put(fd, e);
            System.out.println("registered = " + channel);
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
            ctl(ptrAddress, Ops.Del.value, 0, fd, st.idx);
            unused.add(st);
            st.cleanupNativeResources(unsafe);
            st.handler.onRemove();
        }
    }

    private void addFd(int eventTypes, int fd, State st) {
        st.setNativeStructureAddress(ctl(ptrAddress, Ops.Add.value, eventTypes, fd, st.idx));
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
        Add(1), Del(2);

        private final int value;

        Ops(int value) {
            this.value = value;
        }
    }

    enum EventTypes {
        EPOLLIN(1);

        public final int value;

        EventTypes(int value) {
            this.value = value;
        }
    }
}
