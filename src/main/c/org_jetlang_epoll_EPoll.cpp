#include <jni.h>
#include <sys/epoll.h>
#include "org_jetlang_epoll_EPoll.h"
#include <cstdlib>
#include <unistd.h>
#include <sys/eventfd.h>
#include <stdio.h>
#include <netinet/ip.h>
#include <string.h>
#include <sys/uio.h>
#include <errno.h>

struct epoll_state {
   int fd;
   int efd;
   struct epoll_event * events;
   int max_events;
   struct epoll_event efd_event;

   int udp_rcv_len;
   struct mmsghdr * udp_rcv;
};

JNIEXPORT jint JNICALL Java_org_jetlang_epoll_EPoll_select
  (JNIEnv *, jclass, jlong ptrAddress, jint timeout){
    struct epoll_state *state = (struct epoll_state *) ptrAddress;
    int result = epoll_wait(state->fd, state->events, state->max_events, timeout);
//    printf("epoll wait %d\n", result);
//    printf("events_address %p\n", &state->events);
//    printf("events_address.fd %p\n", &state->events[0].data.fd);
//    fflush(stdout);
    return result;
 }


JNIEXPORT jlong JNICALL Java_org_jetlang_epoll_EPoll_getEventArrayAddress
  (JNIEnv *, jclass, jlong ptrAddress){
    struct epoll_state *state = (struct epoll_state *) ptrAddress;
    return (jlong) state->events;
}


JNIEXPORT jlong JNICALL Java_org_jetlang_epoll_EPoll_getReadBufferAddress
  (JNIEnv *, jclass, jlong ptrAddress, jint idx){
    struct epoll_state *state = (struct epoll_state *) ptrAddress;
    return (jlong) state->udp_rcv[idx].msg_hdr.msg_iov->iov_base;
}

JNIEXPORT jint JNICALL Java_org_jetlang_epoll_EPoll_getEpollEventSize
  (JNIEnv *, jclass){
    return sizeof(struct epoll_event);
}

JNIEXPORT jlong JNICALL Java_org_jetlang_epoll_EPoll_init
  (JNIEnv *, jclass, jint maxSelectedEvents, jint maxDatagramsPerRead, jint readBufferBytes){
    int epoll_fd = epoll_create1(0);
    printf("epoll_fd %d\n", epoll_fd);
    struct epoll_state *state = (struct epoll_state *) malloc(sizeof(struct epoll_state));
    state->fd = epoll_fd;
    state->events = (struct epoll_event *) malloc(maxSelectedEvents * (sizeof(struct epoll_event)));
    state->max_events = maxSelectedEvents;
    state->efd = eventfd(0, EFD_NONBLOCK);
    state->efd_event.events = EPOLLHUP | EPOLLERR | EPOLLIN;
    state->efd_event.data.u32 = 0;
    epoll_ctl(epoll_fd, EPOLL_CTL_ADD, state->efd, &state->efd_event);
    state->udp_rcv_len = maxDatagramsPerRead;
    state->udp_rcv = (struct mmsghdr *) malloc( maxDatagramsPerRead * (sizeof(struct mmsghdr)));
    memset(state->udp_rcv, 0, maxDatagramsPerRead * (sizeof(struct mmsghdr)));
    for (int i = 0; i < maxDatagramsPerRead; i++) {
       char* buffer = (char *) malloc(readBufferBytes);
       struct iovec *io = (struct iovec *)malloc(sizeof(struct iovec));
       io->iov_base = buffer;
       io->iov_len = readBufferBytes;
       state->udp_rcv[i].msg_hdr.msg_iov = io;
       state->udp_rcv[i].msg_hdr.msg_iovlen= 1;
    }
    return (jlong) state;
  }

JNIEXPORT jlong JNICALL Java_org_jetlang_epoll_EPoll_recvmmsg
    (JNIEnv *, jclass, jlong ptrAddress, jint fd){
    struct epoll_state *state = (struct epoll_state *) ptrAddress;
    //printf("to receive %d\n", state->udp_rcv_len);
    int result = recvmmsg(fd, state->udp_rcv, state->udp_rcv_len, 0, NULL);
    if(result != 1){
        printf("result %d errno %d fd %d\n", result, errno, fd);
        fflush(stdout);
    }
    return result;
}

JNIEXPORT void JNICALL Java_org_jetlang_epoll_EPoll_freeNativeMemory
  (JNIEnv *, jclass, jlong ptrAddress){
    struct epoll_state *state = (struct epoll_state *) ptrAddress;
    close(state->fd);
    close(state->efd);
    free(state->events);
    free(state->udp_rcv->msg_hdr.msg_iov->iov_base);
    free(state->udp_rcv->msg_hdr.msg_iov);
    free(state->udp_rcv);
    free(state);
  }

JNIEXPORT void JNICALL Java_org_jetlang_epoll_EPoll_interrupt
  (JNIEnv *, jclass, jlong ptrAddress){
      struct epoll_state *state = (struct epoll_state *) ptrAddress;
      uint64_t d;
      write(state->efd, &d, sizeof(uint64_t));
  }

JNIEXPORT void JNICALL Java_org_jetlang_epoll_EPoll_clearInterrupt
  (JNIEnv *, jclass, jlong ptrAddress){
      struct epoll_state *state = (struct epoll_state *) ptrAddress;
      uint64_t d;
      read(state->efd, &d, sizeof(uint64_t));
  }


JNIEXPORT jlong JNICALL Java_org_jetlang_epoll_EPoll_ctl
  (JNIEnv *, jclass, jlong ptrAddress, jint op, jint eventTypes, jint fd, jint idx){
    struct epoll_state *state = (struct epoll_state *) ptrAddress;
    struct epoll_event *event = (struct epoll_event *) malloc(sizeof(struct epoll_event));
    event->events = eventTypes;
    event->data.u32 = idx;
    int result = epoll_ctl(state->fd, op, fd, event);
    //printf("%d ctl fd %d result %d errno %d\n", op, fd, result, errno);
    return (jlong) event;
  }
