package br.senai.realsensemapper.domain

import br.senai.realsensemapper.domain.CameraEvent.*
import br.senai.realsensemapper.domain.CameraState.*
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraStateMachineTest {
    @Test fun happy_path() {
        var s = DISCONNECTED
        s = nextState(s, ATTACHED); assertEquals(CONNECTED, s)
        s = nextState(s, STREAM_STARTED); assertEquals(STREAMING, s)
        s = nextState(s, RECORD_STARTED); assertEquals(RECORDING, s)
        s = nextState(s, RECORD_STOPPED); assertEquals(STREAMING, s)
        s = nextState(s, STREAM_STOPPED); assertEquals(CONNECTED, s)
    }

    @Test fun detach_from_any_state_disconnects() {
        for (s in CameraState.entries) {
            assertEquals(DISCONNECTED, nextState(s, DETACHED))
        }
    }

    @Test fun invalid_events_keep_state() {
        assertEquals(DISCONNECTED, nextState(DISCONNECTED, RECORD_STARTED))
        assertEquals(CONNECTED, nextState(CONNECTED, RECORD_STOPPED))
        assertEquals(RECORDING, nextState(RECORDING, ATTACHED))
    }
}
