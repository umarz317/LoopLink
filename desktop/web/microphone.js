class Capture extends AudioWorkletProcessor {
  constructor() { super(); this.buffer = new Float32Array(960); this.offset = 0; }
  process(inputs) {
    const channel = inputs[0]?.[0];
    if (channel) for (const sample of channel) {
      this.buffer[this.offset++] = sample;
      if (this.offset === this.buffer.length) {
        this.port.postMessage(this.buffer, [this.buffer.buffer]);
        this.buffer = new Float32Array(960); this.offset = 0;
      }
    }
    return true;
  }
}
registerProcessor('carlink-capture', Capture);
