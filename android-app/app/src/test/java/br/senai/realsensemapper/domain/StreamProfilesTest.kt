package br.senai.realsensemapper.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamProfilesTest {
    @Test fun usb3_descriptor() =
        assertEquals(StreamProfiles.USB3, StreamProfiles.forUsbDescriptor("3.2"))

    @Test fun usb2_descriptor() =
        assertEquals(StreamProfiles.USB2, StreamProfiles.forUsbDescriptor("2.1"))

    @Test fun unknown_descriptor_uses_safe_profile() =
        assertEquals(StreamProfiles.USB2, StreamProfiles.forUsbDescriptor(null))

    @Test fun usb3_profile_matches_spec() {
        val p = StreamProfiles.USB3
        assertEquals(848, p.depthWidth); assertEquals(480, p.depthHeight)
        assertEquals(1280, p.colorWidth); assertEquals(720, p.colorHeight)
        assertEquals(30, p.fps)
    }

    @Test fun usb2_profile_matches_spec() {
        val p = StreamProfiles.USB2
        assertEquals(640, p.depthWidth); assertEquals(480, p.depthHeight)
        assertEquals(640, p.colorWidth); assertEquals(480, p.colorHeight)
        assertEquals(15, p.fps)
    }
}
