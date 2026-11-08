#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

static int fd = -1;

static int emit(unsigned short type, unsigned short code, int value) {
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = type; ev.code = code; ev.value = value;
    return write(fd, &ev, sizeof(ev)) == sizeof(ev) ? 0 : -1;
}
static int sync_events(void) { return emit(EV_SYN, SYN_REPORT, 0); }
static int read_full(void *buf, size_t n) {
    unsigned char *p = (unsigned char *)buf;
    while (n) {
        ssize_t r = read(STDIN_FILENO, p, n);
        if (r == 0) return 0;
        if (r < 0) { if (errno == EINTR) continue; return -1; }
        p += r; n -= (size_t)r;
    }
    return 1;
}
static int create_mouse(void) {
    fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) return -1;
    if (ioctl(fd, UI_SET_EVBIT, EV_SYN) < 0 ||
        ioctl(fd, UI_SET_EVBIT, EV_KEY) < 0 ||
        ioctl(fd, UI_SET_EVBIT, EV_REL) < 0 ||
        ioctl(fd, UI_SET_KEYBIT, BTN_MOUSE) < 0 ||
        ioctl(fd, UI_SET_RELBIT, REL_X) < 0 ||
        ioctl(fd, UI_SET_RELBIT, REL_Y) < 0) return -2;
    ioctl(fd, UI_SET_KEYBIT, BTN_RIGHT);
    ioctl(fd, UI_SET_KEYBIT, BTN_MIDDLE);
    ioctl(fd, UI_SET_PROPBIT, INPUT_PROP_POINTER);
    struct uinput_user_dev dev;
    memset(&dev, 0, sizeof(dev));
    snprintf(dev.name, UINPUT_MAX_NAME_SIZE, "PG Control Virtual Mouse");
    dev.id.bustype = BUS_USB;
    dev.id.vendor = 0x1209;
    dev.id.product = 0x5047;
    dev.id.version = 1;
    if (write(fd, &dev, sizeof(dev)) != sizeof(dev)) return -3;
    if (ioctl(fd, UI_DEV_CREATE) < 0) return -4;
    usleep(250000);
    return 0;
}
int main(void) {
    if (create_mouse() != 0) {
        fprintf(stderr, "uinput init failed: %s\n", strerror(errno));
        return 10;
    }
    write(STDOUT_FILENO, "READY\n", 6);
    for (;;) {
        unsigned char cmd;
        int rr = read_full(&cmd, 1);
        if (rr <= 0 || cmd == 3) break;
        if (cmd == 1) {
            int32_t delta[2];
            if (read_full(delta, sizeof(delta)) <= 0) break;
            if (delta[0]) emit(EV_REL, REL_X, delta[0]);
            if (delta[1]) emit(EV_REL, REL_Y, delta[1]);
            sync_events();
        } else if (cmd == 2) {
            emit(EV_KEY, BTN_MOUSE, 1); sync_events();
            emit(EV_KEY, BTN_MOUSE, 0); sync_events();
        }
    }
    ioctl(fd, UI_DEV_DESTROY);
    close(fd);
    return 0;
}
