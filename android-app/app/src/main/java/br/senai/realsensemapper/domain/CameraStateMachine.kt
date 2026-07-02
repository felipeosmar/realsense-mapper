package br.senai.realsensemapper.domain

enum class CameraState { DISCONNECTED, CONNECTED, STREAMING, RECORDING }

enum class CameraEvent {
    ATTACHED, DETACHED, STREAM_STARTED, STREAM_STOPPED, RECORD_STARTED, RECORD_STOPPED
}

/** Transições válidas; evento inválido mantém o estado (robustez a callbacks USB duplicados). */
fun nextState(state: CameraState, event: CameraEvent): CameraState = when (event) {
    CameraEvent.DETACHED -> CameraState.DISCONNECTED
    CameraEvent.ATTACHED ->
        if (state == CameraState.DISCONNECTED) CameraState.CONNECTED else state
    CameraEvent.STREAM_STARTED ->
        if (state == CameraState.CONNECTED) CameraState.STREAMING else state
    CameraEvent.STREAM_STOPPED ->
        if (state == CameraState.STREAMING) CameraState.CONNECTED else state
    CameraEvent.RECORD_STARTED ->
        if (state == CameraState.STREAMING) CameraState.RECORDING else state
    CameraEvent.RECORD_STOPPED ->
        if (state == CameraState.RECORDING) CameraState.STREAMING else state
}
