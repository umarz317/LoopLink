const $ = id => document.getElementById(id);
let token, socket, mediaPort, lastPhase, phase = 'idle', startedAt = 0, pending = false;
let decoder, videoConfig, needsKey = true, rendered = 0, firstFrame = false;
let context, microphone, capture, micSource, micConfig, micEncoder, micPending = [], micPosition = 0, micTimestamp = 0;
const audioStreams = new Map(), sources = new Set();
const canvas = $('canvas'), drawing = canvas.getContext('2d', {alpha: false});
const events = [];
export const mediaStats = {audioBuffers:0};
function log(message, time = new Date().toISOString()) {
  events.push(`${new Date(time).toLocaleTimeString()}  ${message}`);
  if (events.length > 200) events.shift();
  $('log').textContent = events.join('\n'); $('log').scrollTop = $('log').scrollHeight;
}
function notice(message) { $('notice').hidden = !message; $('notice').textContent = message || ''; }
async function api(path, data) {
  const response = await fetch(`/api/${path}`, data === undefined ? {} : {
    method: 'POST', headers: {'Content-Type': 'application/json', 'X-CarLink-Token': token}, body: JSON.stringify(data)
  });
  const value = await response.json(); if (!response.ok) throw new Error(value.error || 'Request failed'); return value;
}
function send(value) { if (socket?.readyState === WebSocket.OPEN) socket.send(JSON.stringify(value)); }
function status(value) {
  phase = value.phase; startedAt = value.startedAt; document.body.dataset.phase = phase;
  const active = !['idle', 'error'].includes(phase), live = phase === 'streaming';
  $('phase').textContent = ({idle:'Not connected',error:'Connection failed',starting:'Starting…',pairing:'Connecting…',waiting:'Waiting for iPhone…',connected:'iPhone connected',streaming:'iPhone connected'})[phase] || phase;
  // Settings stay out of the way while video plays and come back when the session ends.
  if (phase !== lastPhase) { if (!active) showPanel(true); else if (live) showPanel(false); lastPhase = phase; }
  $('message').textContent = value.message; $('audio').textContent = Number(value.audioPackets || 0).toLocaleString();
  $('settings').disabled = active || pending; $('connect').hidden = active; $('disconnect').hidden = !active;
  $('connect').disabled = pending || !value.identityAvailable || !('VideoDecoder' in window);
  // Fix video is most useful while connected but still waiting for the first frame.
  document.querySelectorAll('[data-action]').forEach(button => {button.disabled = button.dataset.action === 'keyframe' ? !(live || phase === 'connected') : !live || (button.id === 'siri' && !$('microphone').checked);});
  if (!live && !['connected'].includes(phase)) { $('canvas').hidden = true; $('empty').hidden = false; }
  if (!pending && (phase === 'idle' || phase === 'error')) { resetMedia(); stopMicrophone(); }
  if (phase === 'error') notice(value.message);
  $('identity').textContent = value.identityAvailable ? 'Accessory identity available locally.' : 'Accessory identity missing. See desktop/README.md for setup.';
  $('identity-dot').className = value.identityAvailable ? 'ready' : '';
}
function connectSocket() {
  socket = new WebSocket(`ws://${location.hostname}:${mediaPort}/media?token=${encodeURIComponent(token)}`);
  socket.binaryType = 'arraybuffer';
  socket.onmessage = event => {
    try {
      if (typeof event.data === 'string') {
        const value = JSON.parse(event.data);
        if (value.event === 'status') status(value);
        else if (value.event === 'log') log(value.message, value.time);
        // The phone resends its codec config with each requested keyframe; reconfiguring would request another.
        else if (value.event === 'videoConfig' && value.stream === 110) {
          if (value.config !== videoConfig?.config || decoder?.state !== 'configured') configureVideo(value);
        }
        else if (value.event === 'audioConfig') configureAudio(value);
        else if (value.event === 'audioStop') stopAudio(value.stream);
        else if (value.event === 'screen' && !value.active) resetVideo();
        else if (value.event === 'microphoneConfig') configureMicrophone(value);
        else if (value.event === 'microphoneStop') clearMicCodec();
        else if (value.event === 'inputError') log(value.message);
        else if (value.event === 'recover') { needsKey = true; send({action:'keyframe'}); }
      } else {
        const view = new DataView(event.data); if (view.byteLength < 13) return;
        const kind = view.getUint8(0), stream = view.getInt32(1), timestamp = Number(view.getBigUint64(5));
        const payload = new Uint8Array(event.data, 13);
        if (kind === 2 && stream === 110) videoFrame(payload, timestamp);
        else if (kind === 3) audioFrame(stream, payload, timestamp);
      }
    } catch (error) { log(`Media: ${error.message}`); }
  };
  socket.onclose = () => {
    resetMedia(); stopMicrophone();
    $('phase').textContent = 'Receiver offline'; $('message').textContent = 'The local receiver stopped. Keep scripts/desktop.sh running, then reload this page.';
    $('canvas').hidden = true; $('empty').hidden = false;
    document.querySelectorAll('[data-action], #connect').forEach(button => {button.disabled = true;});
    notice('Browser connection closed. Reload after restarting the receiver.');
  };
  socket.onerror = () => notice('Cannot reach the local media channel. Check the receiver terminal.');
}
function decode64(text) { return Uint8Array.from(atob(text), character => character.charCodeAt(0)); }
function resetVideo() {
  if (decoder && decoder.state !== 'closed') decoder.close(); decoder = null;
  needsKey = true; firstFrame = false; $('video-light').className = '';
}
function configureVideo(value) {
  resetVideo(); const bytes = decode64(value.config); videoConfig = value;
  if (bytes.length < 7) throw new Error('Invalid H.264 configuration');
  const codec = `avc1.${[bytes[1], bytes[2], bytes[3]].map(x => x.toString(16).padStart(2, '0')).join('')}`;
  decoder = new VideoDecoder({output(frame) {
    try {
      canvas.width = frame.displayWidth; canvas.height = frame.displayHeight;
      drawing.drawImage(frame, 0, 0, canvas.width, canvas.height);
      $('canvas').hidden = false; $('empty').hidden = true;
      $('frames').textContent = (++rendered).toLocaleString(); $('video-light').className = 'live';
      if (!firstFrame) { firstFrame = true; send({action:'rendered'}); }
    } finally { frame.close(); }
  }, error(error) { log(`Video decoder: ${error.message}`); needsKey = true; send({action:'keyframe'}); }});
  decoder.configure({codec, description: bytes, optimizeForLatency:true});
  send({action:'keyframe'});
}
// Convert the protocol's Annex B NAL units to the avcC decoder's length-prefixed layout.
export function avccFrame(bytes) {
  const starts = [];
  for (let i = 0; i + 3 < bytes.length; i++) {
    if (bytes[i] === 0 && bytes[i+1] === 0 && bytes[i+2] === 1) { starts.push([i,3]); i += 2; }
    else if (bytes[i] === 0 && bytes[i+1] === 0 && bytes[i+2] === 0 && bytes[i+3] === 1) { starts.push([i,4]); i += 3; }
  }
  if (!starts.length) throw new Error('No H.264 NAL units');
  const nalus = starts.map(([start,length], index) => bytes.subarray(start+length, starts[index+1]?.[0] ?? bytes.length)).filter(nalu => nalu.length);
  const result = new Uint8Array(nalus.reduce((size,nalu) => size+4+nalu.length,0)); const view = new DataView(result.buffer);
  let offset=0, key=false;
  for (const nalu of nalus) {view.setUint32(offset,nalu.length); result.set(nalu,offset+4); offset+=4+nalu.length; if ((nalu[0]&31)===5) key=true;}
  return {data:result,key};
}
function videoFrame(bytes,timestamp) {
  if (!decoder || decoder.state !== 'configured') { if(videoConfig) configureVideo(videoConfig); return; }
  const frame = avccFrame(bytes);
  if (decoder.decodeQueueSize > 4) { needsKey=true; send({action:'keyframe'}); return; }
  if (needsKey && !frame.key) return;
  needsKey=false; decoder.decode(new EncodedVideoChunk({type:frame.key?'key':'delta',timestamp,data:frame.data}));
}
export async function unlockAudio() {
  if (!context) context = new AudioContext({sampleRate:48000,latencyHint:'interactive'});
  await context.resume();
}
function scheduleAudio(buffer, stream) {
  if (!context || context.state !== 'running') return;
  const now=context.currentTime;
  if (stream.nextTime > now+0.35) stream.nextTime=now+0.06;
  const source=context.createBufferSource(); source.buffer=buffer; source.connect(context.destination);
  stream.nextTime=Math.max(stream.nextTime || 0,now+0.035);
  source.start(stream.nextTime); stream.nextTime += buffer.duration;
  mediaStats.audioBuffers++;
  sources.add(source); source.onended=()=>{sources.delete(source);source.disconnect();};
}
function stopAudio(id) {
  const stream=audioStreams.get(id);
  if (stream?.decoder && stream.decoder.state !== 'closed') stream.decoder.close();
  audioStreams.delete(id);
}
function configureAudio(value) {
  stopAudio(value.stream); const stream={...value,nextTime:0}; audioStreams.set(value.stream,stream);
  if (value.codec === 'LPCM') return;
  if (!('AudioDecoder' in window)) { log('Audio requires Chrome with WebCodecs support.'); return; }
  const config={codec:value.codec==='OPUS'?'opus':'mp4a.40.2',sampleRate:value.sampleRate,numberOfChannels:value.channels};
  if (value.codec==='AAC_LC') {
    const rates=[96000,88200,64000,48000,44100,32000,24000,22050,16000,12000,11025,8000,7350];
    const index=rates.indexOf(value.sampleRate); if(index<0) throw new Error('Unsupported AAC sample rate');
    config.description=new Uint8Array([(2<<3)|(index>>1),((index&1)<<7)|(value.channels<<3)]);
  }
  stream.decoder=new AudioDecoder({output(data) {
    try {
      if (!context) return;
      const buffer=context.createBuffer(data.numberOfChannels,data.numberOfFrames,data.sampleRate);
      for(let channel=0;channel<data.numberOfChannels;channel++) data.copyTo(buffer.getChannelData(channel),{planeIndex:channel,format:'f32-planar'});
      scheduleAudio(buffer,stream);
    } finally { data.close(); }
  },error(error) {log(`Audio: ${error.message}`);}});
  stream.decoder.configure(config);
}
function audioFrame(id,payload,timestamp) {
  const stream=audioStreams.get(id); if(!stream || !context) return;
  if (stream.codec==='LPCM') {
    const count=Math.floor(payload.length/(2*stream.channels)); if(!count) return;
    const buffer=context.createBuffer(stream.channels,count,stream.sampleRate), view=new DataView(payload.buffer,payload.byteOffset,payload.byteLength);
    for(let channel=0;channel<stream.channels;channel++) {const plane=buffer.getChannelData(channel);for(let i=0;i<count;i++)plane[i]=view.getInt16((i*stream.channels+channel)*2,false)/32768;}
    scheduleAudio(buffer,stream); return;
  }
  if(!stream.decoder || stream.decoder.state!=='configured' || stream.decoder.decodeQueueSize>12) return;
  // Strip ADTS when the phone supplies it; WebCodecs is configured for raw AAC access units.
  if(stream.codec==='AAC_LC' && payload.length>7 && payload[0]===255 && (payload[1]&0xf6)===0xf0) payload=payload.subarray(payload[1]&1?7:9);
  stream.decoder.decode(new EncodedAudioChunk({type:'key',timestamp:Math.round(timestamp*1e6/stream.sampleRate),data:payload}));
}
function resetMedia() {
  resetVideo(); videoConfig=null; rendered=0; $('frames').textContent='0';
  for(const id of [...audioStreams.keys()]) stopAudio(id);
  for(const source of sources) {try{source.stop();}catch{}} sources.clear();
}
async function startMicrophone() {
  if (!$('microphone').checked) return;
  microphone=await navigator.mediaDevices.getUserMedia({audio:{channelCount:1,echoCancellation:true,noiseSuppression:true},video:false});
  await context.audioWorklet.addModule('/microphone.js');
  micSource=context.createMediaStreamSource(microphone); capture=new AudioWorkletNode(context,'carlink-capture');
  capture.port.onmessage=event=>captureMicrophone(event.data);
  micSource.connect(capture); capture.connect(context.destination);
}
function clearMicCodec() {if(micEncoder && micEncoder.state!=='closed')micEncoder.close();micEncoder=null;micConfig=null;micPending=[];micPosition=0;micTimestamp=0;}
function stopMicrophone() {
  clearMicCodec(); capture?.disconnect(); micSource?.disconnect();
  microphone?.getTracks().forEach(track=>track.stop()); microphone=null;capture=null;micSource=null;
}
function configureMicrophone(value) {
  clearMicCodec(); if(!microphone) {log('Microphone stream requested, but browser capture is disabled.');return;}
  if(value.codec==='AAC_LC'){log('AAC microphone uplink is not supported.');return;}
  micConfig=value;
  if(value.codec==='OPUS') {
    if(!('AudioEncoder' in window)){log('Opus microphone requires Chrome AudioEncoder support.');micConfig=null;return;}
    micEncoder=new AudioEncoder({output(chunk){const bytes=new Uint8Array(chunk.byteLength);chunk.copyTo(bytes);sendMic(bytes);},error(error){log(`Microphone: ${error.message}`);clearMicCodec();}});
    micEncoder.configure({codec:'opus',sampleRate:48000,numberOfChannels:value.channels,bitrate:value.bitrate||64000,opus:{frameDuration:20000}});
  }
}
function sendMic(bytes) {
  if(!micConfig || socket?.readyState!==WebSocket.OPEN || socket.bufferedAmount>65536)return;
  const packet=new Uint8Array(5+bytes.length);packet[0]=4;new DataView(packet.buffer).setInt32(1,micConfig.stream);packet.set(bytes,5);socket.send(packet);
}
function captureMicrophone(samples) {
  if(!micConfig)return;
  micPending.push(...samples);
  const rate=micConfig.codec==='OPUS'?48000:micConfig.sampleRate,ratio=context.sampleRate/rate;
  const count=micConfig.codec==='OPUS'?960:micConfig.samples;
  while(micPending.length>micPosition+count*ratio) {
    const mono=new Float32Array(count);
    for(let i=0;i<count;i++){const position=micPosition+i*ratio,base=Math.floor(position),fraction=position-base;mono[i]=micPending[base]*(1-fraction)+micPending[base+1]*fraction;}
    micPosition+=count*ratio;
    const consumed=Math.floor(micPosition);micPending.splice(0,consumed);micPosition-=consumed;
    if(micEncoder){
      if(micEncoder.encodeQueueSize>4)continue;
      const data=new Float32Array(count*micConfig.channels);for(let ch=0;ch<micConfig.channels;ch++)data.set(mono,ch*count);
      const frame=new AudioData({format:'f32-planar',sampleRate:rate,numberOfFrames:count,numberOfChannels:micConfig.channels,timestamp:micTimestamp,data});
      micEncoder.encode(frame);frame.close();micTimestamp+=Math.round(count*1e6/rate);
    }else{
      const bytes=new Uint8Array(count*micConfig.channels*2),view=new DataView(bytes.buffer);
      for(let i=0;i<count;i++)for(let ch=0;ch<micConfig.channels;ch++)view.setInt16((i*micConfig.channels+ch)*2,Math.round(Math.max(-1,Math.min(1,mono[i]))*32767),false);
      sendMic(bytes);
    }
  }
}
$('setup-form').onsubmit=async event=>{
  event.preventDefault();if(pending)return;pending=true;notice('');$('connect').disabled=true;
  try {
    await unlockAudio();await startMicrophone();
    const [width,height]=$('size').value.split(',').map(Number);
    const config={width,height,fps:Number($('fps').value),microphone:$('microphone').checked};
    resetMedia();$('resolution').textContent=`Stream ${width}×${height} · ${config.fps} fps`;
    const value=await api('connect',config);
    pending=false;status(value);
  } catch(error){notice(error.message);stopMicrophone();pending=false;$('connect').disabled=false;}
};
$('disconnect').onclick=async()=>{try{send({action:'release'});status(await api('disconnect',{}));}catch(error){notice(error.message);}};
document.querySelectorAll('[data-action]').forEach(button=>{button.onclick=()=>send({action:button.dataset.action});});
function showPanel(open){$('panel').hidden=!open;$('settings-toggle').setAttribute('aria-expanded',String(open));}
$('settings-toggle').onclick=()=>showPanel($('panel').hidden);
$('mute').onclick=async()=>{
  const muted=$('mute').getAttribute('aria-pressed')==='true';
  if(context) await (muted?context.resume():context.suspend());
  $('mute').setAttribute('aria-pressed',String(!muted));$('mute').title=$('mute').ariaLabel=muted?'Mute':'Unmute';
};
$('fullscreen').onclick=()=>{if(document.fullscreenElement)document.exitFullscreen();else $('screen').requestFullscreen().catch(error=>notice(error.message));};
let pointer=null,lastPoint={x:0,y:0};
function point(event){
  const bounds=canvas.getBoundingClientRect(), scale=Math.min(bounds.width/canvas.width,bounds.height/canvas.height);
  const width=canvas.width*scale,height=canvas.height*scale;
  return{x:Math.max(0,Math.min(1,(event.clientX-bounds.left-(bounds.width-width)/2)/width)),
    y:Math.max(0,Math.min(1,(event.clientY-bounds.top-(bounds.height-height)/2)/height))};
}
canvas.onpointerdown=event=>{if(phase!=='streaming'||pointer!==null)return;event.preventDefault();pointer=event.pointerId;canvas.setPointerCapture(pointer);lastPoint=point(event);send({action:'touch',...lastPoint,down:true});};
canvas.onpointermove=event=>{if(event.pointerId!==pointer)return;lastPoint=point(event);send({action:'touch',...lastPoint,down:true});};
function releasePointer(){if(pointer!==null){send({action:'touch',...lastPoint,down:false});pointer=null;}}
canvas.onpointerup=releasePointer;canvas.onpointercancel=releasePointer;canvas.onlostpointercapture=releasePointer;
window.addEventListener('blur',releasePointer);
$('screen').onkeydown=event=>{if(phase!=='streaming')return;const action=event.key==='Escape'?'back':event.key.toLowerCase()==='h'?'home':null;if(action){event.preventDefault();send({action});}};
document.addEventListener('visibilitychange',()=>{releasePointer();if(!document.hidden){needsKey=true;send({action:'keyframe'});}});
setInterval(async()=>{
  try{status(await api('status'));}catch{}
  $('elapsed').textContent=startedAt&&!['idle','error'].includes(phase)?`${Math.floor((Date.now()-startedAt)/60000)}:${String(Math.floor((Date.now()-startedAt)/1000)%60).padStart(2,'0')}`:'—';
},2000);
async function initialize(){
  try{
    const value=await api('bootstrap');token=value.token;mediaPort=value.mediaPort;
    const config=value.config;
    $('fps').value=config.fps;$('size').value=`${config.width},${config.height}`;$('microphone').checked=config.microphone;
    $('resolution').textContent=`Stream ${config.width}×${config.height} · ${config.fps} fps`;
    value.logs.forEach(entry=>log(entry.message,entry.time));status(value.status);connectSocket();
    if(!('VideoDecoder' in window))notice('Open this page in Google Chrome for H.264 video and audio decoding.');
  }catch(error){notice(`Receiver unavailable: ${error.message}`);$('phase').textContent='Receiver offline';}
}
initialize();
