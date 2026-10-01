// CarLink Desk USB helper: lists USB iPhones and bridges com.apple.carkit.service (iAP2) over stdio.
// macOS usbmuxd and the Mac's existing trust record provide the Lockdown session; no raw USB access.
// stdout is exclusively the carkit byte stream in connect mode; diagnostics use stderr.
#include <CoreFoundation/CoreFoundation.h>
#include <IOKit/IOKitLib.h>
#include <libimobiledevice/libimobiledevice.h>
#include <libimobiledevice/lockdown.h>
#include <libimobiledevice/service.h>
#include <poll.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static int intProperty(io_registry_entry_t entry, CFStringRef key) {
    int value = -1;
    CFTypeRef number = IORegistryEntryCreateCFProperty(entry, key, kCFAllocatorDefault, 0);
    if (number && CFGetTypeID(number) == CFNumberGetTypeID()) CFNumberGetValue(number, kCFNumberIntType, &value);
    if (number) CFRelease(number);
    return value;
}

// Finds the BSD name of the iPhone's first CDC-NCM function, which carries wired CarPlay networking.
static int ncmInterface(const char *udid, char *bsdName, size_t size) {
    char serial[64] = {0};
    for (size_t i = 0, j = 0; udid[i] && j + 1 < sizeof serial; i++) if (udid[i] != '-') serial[j++] = udid[i];
    io_iterator_t devices = 0;
    if (IOServiceGetMatchingServices(kIOMainPortDefault, IOServiceMatching("IOUSBHostDevice"), &devices) != KERN_SUCCESS) return -1;
    int best = -1;
    io_service_t device;
    while ((device = IOIteratorNext(devices))) {
        CFStringRef value = IORegistryEntryCreateCFProperty(device, CFSTR("USB Serial Number"), kCFAllocatorDefault, 0);
        char text[64] = {0};
        if (value && CFGetTypeID(value) == CFStringGetTypeID()) CFStringGetCString(value, text, sizeof text, kCFStringEncodingUTF8);
        if (value) CFRelease(value);
        if (intProperty(device, CFSTR("idVendor")) == 0x05ac && !strcasecmp(text, serial)) {
            io_iterator_t children = 0;
            IORegistryEntryCreateIterator(device, kIOServicePlane, kIORegistryIterateRecursively, &children);
            io_registry_entry_t child;
            while ((child = IOIteratorNext(children))) {
                int number = intProperty(child, CFSTR("bInterfaceNumber"));
                if (IOObjectConformsTo(child, "IOUSBHostInterface") && intProperty(child, CFSTR("bInterfaceClass")) == 2 &&
                    intProperty(child, CFSTR("bInterfaceSubClass")) == 13 && (best < 0 || number < best)) {
                    CFStringRef name = IORegistryEntrySearchCFProperty(child, kIOServicePlane, CFSTR("BSD Name"),
                        kCFAllocatorDefault, kIORegistryIterateRecursively);
                    if (name && CFGetTypeID(name) == CFStringGetTypeID() && CFStringGetCString(name, bsdName, size, kCFStringEncodingUTF8))
                        best = number;
                    if (name) CFRelease(name);
                }
                IOObjectRelease(child);
            }
            IOObjectRelease(children);
        }
        IOObjectRelease(device);
    }
    IOObjectRelease(devices);
    return best;
}

static void jsonString(const char *text) {
    putchar('"');
    for (; *text; text++) {
        if (*text == '"' || *text == '\\') printf("\\%c", *text);
        else if ((unsigned char)*text < 0x20) printf("\\u%04x", *text);
        else putchar(*text);
    }
    putchar('"');
}

static int devices(void) {
    idevice_info_t *list = NULL;
    int count = 0;
    if (idevice_get_device_list_extended(&list, &count) != IDEVICE_E_SUCCESS) count = 0;
    printf("{\"devices\":[");
    int written = 0;
    for (int i = 0; i < count; i++) {
        if (list[i]->conn_type != CONNECTION_USBMUXD) continue;
        char name[256] = "iPhone", bsd[32] = "";
        idevice_t device = NULL;
        lockdownd_client_t lockdown = NULL;
        int trusted = 0;
        if (idevice_new_with_options(&device, list[i]->udid, IDEVICE_LOOKUP_USBMUX) == IDEVICE_E_SUCCESS &&
            lockdownd_client_new_with_handshake(device, &lockdown, "carlink-desk") == LOCKDOWN_E_SUCCESS) {
            char *deviceName = NULL;
            if (lockdownd_get_device_name(lockdown, &deviceName) == LOCKDOWN_E_SUCCESS && deviceName) {
                snprintf(name, sizeof name, "%s", deviceName); free(deviceName);
            }
            trusted = 1;
        }
        if (lockdown) lockdownd_client_free(lockdown);
        if (device) idevice_free(device);
        int interfaceNumber = ncmInterface(list[i]->udid, bsd, sizeof bsd);
        printf("%s{\"udid\":", written++ ? "," : ""); jsonString(list[i]->udid);
        printf(",\"name\":"); jsonString(name);
        printf(",\"trusted\":%s,\"interface\":", trusted ? "true" : "false"); jsonString(bsd);
        printf(",\"interfaceNumber\":%d}", interfaceNumber);
    }
    printf("]}\n");
    if (list) idevice_device_list_extended_free(list);
    return 0;
}

static int connectCarkit(const char *udid) {
    idevice_t device = NULL;
    if (idevice_new_with_options(&device, udid, IDEVICE_LOOKUP_USBMUX) != IDEVICE_E_SUCCESS) {
        fprintf(stderr, "iPhone USB connection unavailable. Reconnect the cable and unlock the iPhone.\n"); return 2;
    }
    lockdownd_client_t lockdown = NULL;
    lockdownd_error_t result = lockdownd_client_new_with_handshake(device, &lockdown, "carlink-desk");
    if (result != LOCKDOWN_E_SUCCESS) {
        fprintf(stderr, "iPhone Lockdown session failed (%d). Unlock the iPhone and tap Trust.\n", result); return 3;
    }
    // The Lockdown session stays open for the service's lifetime.
    lockdownd_service_descriptor_t descriptor = NULL;
    result = lockdownd_start_service(lockdown, "com.apple.carkit.service", &descriptor);
    if (result != LOCKDOWN_E_SUCCESS) { fprintf(stderr, "iPhone CarPlay USB service unavailable (%d)\n", result); return 4; }
    service_client_t service = NULL;
    // service_client_new enables TLS itself when the descriptor requires it.
    if (service_client_new(device, descriptor, &service) != SERVICE_E_SUCCESS) {
        fprintf(stderr, "iPhone CarPlay USB service connection failed\n"); return 4;
    }
    fprintf(stderr, "iPhone CarPlay USB channel open\n");
    // One thread alternates directions: the TLS connection must not be read and written concurrently.
    char buffer[16384];
    struct pollfd input = { .fd = STDIN_FILENO, .events = POLLIN };
    for (;;) {
        if (poll(&input, 1, 2) > 0) {
            ssize_t length = read(STDIN_FILENO, buffer, sizeof buffer);
            if (length <= 0) break;
            for (ssize_t offset = 0; offset < length;) {
                uint32_t sent = 0;
                if (service_send(service, buffer + offset, (uint32_t)(length - offset), &sent) != SERVICE_E_SUCCESS || !sent) {
                    fprintf(stderr, "iPhone CarPlay USB write failed\n"); return 5;
                }
                offset += sent;
            }
        }
        uint32_t received = 0;
        service_error_t error = service_receive_with_timeout(service, buffer, sizeof buffer, &received, 2);
        for (uint32_t offset = 0; offset < received;) {
            ssize_t written = write(STDOUT_FILENO, buffer + offset, received - offset);
            if (written <= 0) return 6;
            offset += (uint32_t)written;
        }
        if (error != SERVICE_E_SUCCESS && error != SERVICE_E_TIMEOUT) {
            fprintf(stderr, "iPhone CarPlay USB channel closed (%d)\n", error); return 6;
        }
    }
    service_client_free(service);
    lockdownd_service_descriptor_free(descriptor);
    lockdownd_client_free(lockdown);
    idevice_free(device);
    return 0;
}

int main(int argc, const char *argv[]) {
    if (argc == 2 && !strcmp(argv[1], "devices")) return devices();
    if (argc == 3 && !strcmp(argv[1], "connect")) return connectCarkit(argv[2]);
    fprintf(stderr, "usage: carlink-usb devices | connect UDID\n");
    return 1;
}
