/**
 * 极简 MQTT 3.1.1 客户端（仅 QoS0 订阅），跑在 wx.connectSocket 上。
 * 小程序没有原生 MQTT，这里直接构造报文，避免引入第三方库。
 */
function utf8(str) {
  var out = [];
  for (var i = 0; i < str.length; i++) {
    var c = str.charCodeAt(i);
    if (c < 0x80) {
      out.push(c);
    } else if (c < 0x800) {
      out.push(0xc0 | (c >> 6), 0x80 | (c & 0x3f));
    } else if (c < 0xd800 || c >= 0xe000) {
      out.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 0x3f), 0x80 | (c & 0x3f));
    } else {
      i++;
      var c2 = 0x10000 + (((c & 0x3ff) << 10) | (str.charCodeAt(i) & 0x3ff));
      out.push(0xf0 | (c2 >> 18), 0x80 | ((c2 >> 12) & 0x3f),
        0x80 | ((c2 >> 6) & 0x3f), 0x80 | (c2 & 0x3f));
    }
  }
  return out;
}

function utf8Decode(bytes) {
  var s = '';
  var i = 0;
  while (i < bytes.length) {
    var c = bytes[i++];
    if (c < 0x80) {
      s += String.fromCharCode(c);
    } else if (c < 0xe0) {
      s += String.fromCharCode(((c & 0x1f) << 6) | (bytes[i++] & 0x3f));
    } else if (c < 0xf0) {
      s += String.fromCharCode(((c & 0x0f) << 12) | ((bytes[i++] & 0x3f) << 6) | (bytes[i++] & 0x3f));
    } else {
      var cp = ((c & 0x07) << 18) | ((bytes[i++] & 0x3f) << 12) |
        ((bytes[i++] & 0x3f) << 6) | (bytes[i++] & 0x3f);
      cp -= 0x10000;
      s += String.fromCharCode(0xd800 + (cp >> 10), 0xdc00 + (cp & 0x3ff));
    }
  }
  return s;
}

function mqttString(str) {
  var b = utf8(str);
  return [(b.length >> 8) & 0xff, b.length & 0xff].concat(b);
}

function encodeRemaining(len) {
  var out = [];
  var l = len;
  do {
    var b = l % 128;
    l = Math.floor(l / 128);
    if (l > 0) b = b | 0x80;
    out.push(b);
  } while (l > 0);
  return out;
}

function toBytes(data) {
  if (typeof data === 'string') return utf8(data);
  if (data instanceof ArrayBuffer) return Array.prototype.slice.call(new Uint8Array(data));
  if (ArrayBuffer.isView(data)) {
    return Array.prototype.slice.call(
      new Uint8Array(data.buffer, data.byteOffset, data.byteLength)
    );
  }
  return null;
}

function Client(opts) {
  this.url = opts.url;
  this.topic = opts.topic;
  this.clientId = opts.clientId || ('wxrid' + Math.random().toString(16).slice(2, 10));
  this.onStatus = function () {};
  this.onMessage = function () {};
  this.buf = [];
  this.connected = false;
  this.manual = false;
  this.socket = null;
  this.pingTimer = null;
  this.retryTimer = null;
  this.packetId = 1;
}

Client.prototype.connect = function () {
  var self = this;
  this.manual = false;
  this.status('connecting');

  var socket = wx.connectSocket({
    url: this.url,
    protocols: ['mqtt'],
    header: {},
    fail: function (err) {
      self.status('error', 'socket 创建失败');
      self.scheduleRetry();
    }
  });

  this.socket = socket;
  try {
    socket.binaryType = 'arraybuffer';
  } catch (e) {}

  socket.onOpen(function () {
    self.sendConnect();
  });

  socket.onMessage(function (res) {
    self.onData(res.data);
  });

  socket.onError(function () {
    self.connected = false;
    self.status('error', '网络错误');
  });

  socket.onClose(function () {
    var wasConnected = self.connected;
    self.connected = false;
    self.stopPing();
    self.status('closed', wasConnected);
    self.scheduleRetry();
  });
};

Client.prototype.close = function () {
  this.manual = true;
  this.connected = false;
  this.stopPing();
  if (this.retryTimer) {
    clearTimeout(this.retryTimer);
    this.retryTimer = null;
  }
  try {
    if (this.socket) this.socket.close({});
  } catch (e) {}
  this.socket = null;
  this.buf = [];
  this.status('idle');
};

Client.prototype.send = function (arr) {
  if (!this.socket) return;
  try {
    var buf = new Uint8Array(arr).buffer;
    this.socket.send({ data: buf });
  } catch (e) {
    this.status('error', '发送失败');
  }
};

Client.prototype.sendConnect = function () {
  var body = mqttString('MQTT')
    .concat([4, 0x02, 0x00, 60])
    .concat(mqttString(this.clientId));
  this.send([0x10].concat(encodeRemaining(body.length)).concat(body));
};

Client.prototype.subscribe = function () {
  var body = [(this.packetId >> 8) & 0xff, this.packetId & 0xff]
    .concat(mqttString(this.topic))
    .concat([0x00]);
  this.packetId = (this.packetId % 65535) + 1;
  this.send([0x82].concat(encodeRemaining(body.length)).concat(body));
};

Client.prototype.startPing = function () {
  var self = this;
  this.stopPing();
  this.pingTimer = setInterval(function () {
    if (self.connected) self.send([0xc0, 0x00]);
  }, 25000);
};

Client.prototype.stopPing = function () {
  if (this.pingTimer) {
    clearInterval(this.pingTimer);
    this.pingTimer = null;
  }
};

Client.prototype.scheduleRetry = function () {
  var self = this;
  if (this.manual || this.retryTimer) return;
  this.retryTimer = setTimeout(function () {
    self.retryTimer = null;
    if (!self.manual) self.connect();
  }, 5000);
};

Client.prototype.onData = function (data) {
  var bytes = toBytes(data);
  if (!bytes || !bytes.length) return;
  this.buf = this.buf.concat(bytes);

  while (true) {
    var b = this.buf;
    if (b.length < 2) return;

    var mult = 1;
    var rem = 0;
    var i = 1;
    var digit;
    do {
      if (i >= b.length) return;
      digit = b[i++];
      rem += (digit & 0x7f) * mult;
      mult *= 128;
      if (mult > 128 * 128 * 128) {
        this.buf = [];
        return;
      }
    } while (digit & 0x80);

    if (b.length < i + rem) return;

    var packet = b.slice(i, i + rem);
    this.buf = b.slice(i + rem);
    this.handlePacket(b[0], packet);
  }
};

Client.prototype.handlePacket = function (header, packet) {
  var type = header >> 4;

  if (type === 2) {
    var rc = packet.length > 1 ? packet[1] : 1;
    if (rc === 0) {
      this.connected = true;
      this.subscribe();
      this.startPing();
      this.status('connected');
    } else {
      this.status('error', 'MQTT 拒绝连接（' + rc + '）');
    }
    return;
  }

  if (type === 3) {
    if (packet.length < 2) return;
    var topicLen = (packet[0] << 8) | packet[1];
    var topic = utf8Decode(packet.slice(2, 2 + topicLen));
    var off = 2 + topicLen;
    var qos = (header >> 1) & 0x03;
    if (qos > 0) off += 2;
    var payload = utf8Decode(packet.slice(off));
    this.onMessage(topic, payload);
  }
};

Client.prototype.status = function (s, info) {
  if (this.onStatus) this.onStatus(s, info);
};

module.exports = {
  Client: Client,
  utf8Decode: utf8Decode
};
