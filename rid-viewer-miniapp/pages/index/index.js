const mqtt = require('../../utils/mqtt');
const demo = require('../../utils/demo');

const DEFAULT_RANGE = 300;

Page({
  data: {
    mode: 'demo',
    statusText: '演示数据 · 非真实信号',
    connected: false,
    room: 'trae-demo',
    drones: []
  },

  onLoad: function () {
    this.alive = true;
    this.drones = [];
    this.sweep = 0;
    this.frame = 0;
    this.demoStart = Date.now();
    this.lastSnapshot = 0;
    this.initCanvas();
    this.startDemo();
    this.timer = setInterval(() => this.tick(), 66);
  },

  onUnload: function () {
    this.alive = false;
    clearInterval(this.timer);
    this.closeClient();
  },

  // ---------------------------------------------------------- 画布

  initCanvas: function () {
    const query = wx.createSelectorQuery();
    query.select('#radar').fields({ node: true, size: true }).exec((res) => {
      if (!this.alive) return;
      const item = res && res[0];
      if (!item || !item.node) return;
      const canvas = item.node;
      const ctx = canvas.getContext('2d');
      let dpr = 2;
      try {
        const info = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync();
        dpr = info.pixelRatio || 2;
      } catch (e) {}
      canvas.width = item.width * dpr;
      canvas.height = item.height * dpr;
      ctx.scale(dpr, dpr);
      this.canvas = canvas;
      this.ctx = ctx;
      this.cw = item.width;
      this.ch = item.height;
      this.draw();
    });
  },

  tick: function () {
    if (!this.alive) return;
    this.sweep = (this.sweep + 0.045) % (Math.PI * 2);
    this.frame++;
    if (this.frame % 8 === 0) this.refreshData();
    this.draw();
  },

  refreshData: function () {
    if (this.data.mode === 'demo') {
      const list = demo.generate((Date.now() - this.demoStart) / 1000);
      this.drones = list;
      getApp().globalData.lastDrones = list;
      this.setData({ drones: list });
    } else if (this.data.mode === 'live') {
      if (this.lastSnapshot && Date.now() - this.lastSnapshot > 20000) {
        this.drones = [];
        this.setData({ drones: [], statusText: '信号中断，等待 Android 端…' });
      }
    }
  },

  draw: function () {
    const ctx = this.ctx;
    if (!ctx) return;
    const w = this.cw;
    const h = this.ch;
    const cx = w / 2;
    const cy = h / 2;
    const R = Math.min(w, h) / 2 - 18;
    ctx.clearRect(0, 0, w, h);
    if (R <= 0) return;

    ctx.beginPath();
    ctx.arc(cx, cy, R, 0, Math.PI * 2);
    ctx.fillStyle = '#0E1828';
    ctx.fill();

    ctx.strokeStyle = '#1D2C44';
    ctx.lineWidth = 1;
    for (let i = 1; i <= 3; i++) {
      ctx.beginPath();
      ctx.arc(cx, cy, R * i / 3, 0, Math.PI * 2);
      ctx.stroke();
    }
    ctx.beginPath();
    ctx.moveTo(cx - R, cy);
    ctx.lineTo(cx + R, cy);
    ctx.moveTo(cx, cy - R);
    ctx.lineTo(cx, cy + R);
    ctx.stroke();

    // 扫描扇形
    ctx.save();
    ctx.translate(cx, cy);
    ctx.rotate(this.sweep);
    ctx.beginPath();
    ctx.moveTo(0, 0);
    ctx.arc(0, 0, R, -0.5, 0);
    ctx.closePath();
    ctx.fillStyle = 'rgba(34,211,238,0.10)';
    ctx.fill();
    ctx.beginPath();
    ctx.moveTo(0, 0);
    ctx.lineTo(R, 0);
    ctx.strokeStyle = 'rgba(34,211,238,0.55)';
    ctx.lineWidth = 1.2;
    ctx.stroke();
    ctx.restore();

    ctx.fillStyle = '#5C7089';
    ctx.font = '11px sans-serif';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillText('北', cx, cy - R - 9);
    ctx.fillText('南', cx, cy + R + 9);
    ctx.fillText('西', cx - R - 9, cy);
    ctx.fillText('东', cx + R + 9, cy);

    const list = this.drones || [];
    let maxD = 0;
    let hasFix = false;
    for (const d of list) {
      if (typeof d.distanceM === 'number') {
        hasFix = true;
        if (d.distanceM > maxD) maxD = d.distanceM;
      }
    }
    const maxRange = Math.max(DEFAULT_RANGE, maxD * 1.15);

    for (const d of list) {
      let angle;
      let ratio;
      if (typeof d.distanceM === 'number') {
        angle = typeof d.bearing === 'number' ? d.bearing : 0;
        ratio = Math.min(1, Math.max(0.06, d.distanceM / maxRange));
      } else {
        angle = Math.abs(hash(d.key)) % 360;
        ratio = Math.min(1, Math.max(0.15, (100 + (d.rssi || -70)) / 60));
      }
      const rad = angle * Math.PI / 180;
      const x = cx + Math.sin(rad) * R * ratio;
      const y = cy - Math.cos(rad) * R * ratio;
      const color = d.dotColor || '#22C55E';

      if (typeof d.distanceM === 'number') {
        ctx.beginPath();
        ctx.arc(x, y, 11, 0, Math.PI * 2);
        ctx.globalAlpha = 0.22;
        ctx.fillStyle = color;
        ctx.fill();
        ctx.globalAlpha = 1;
        ctx.beginPath();
        ctx.arc(x, y, 4, 0, Math.PI * 2);
        ctx.fillStyle = color;
        ctx.fill();
      } else {
        ctx.beginPath();
        ctx.arc(x, y, 5, 0, Math.PI * 2);
        ctx.strokeStyle = color;
        ctx.lineWidth = 1.5;
        ctx.stroke();
      }

      if (list.length <= 8) {
        ctx.fillStyle = '#D8E6F5';
        ctx.font = '9px sans-serif';
        ctx.fillText(d.distText, x, y - 12);
      }
    }

    ctx.beginPath();
    ctx.arc(cx, cy, 2.5, 0, Math.PI * 2);
    ctx.fillStyle = '#22D3EE';
    ctx.fill();

    ctx.fillStyle = '#5C7089';
    ctx.font = '10px sans-serif';
    const scale = maxRange >= 1000
      ? '量程 ' + (maxRange / 1000).toFixed(1) + ' km'
      : '量程 ' + Math.round(maxRange) + ' m';
    ctx.fillText(scale, cx, cy + R + 24);
    if (!hasFix && list.length) {
      ctx.fillText('无 GPS · 半径按信号强度估算', cx, cy + R + 38);
    }
  },

  // ---------------------------------------------------------- 模式

  onRoom: function (e) {
    this.setData({ room: e.detail.value });
  },

  startDemo: function () {
    this.closeClient();
    this.demoStart = Date.now();
    this.lastSnapshot = 0;
    const list = demo.generate(0);
    this.drones = list;
    getApp().globalData.lastDrones = list;
    this.setData({
      mode: 'demo',
      statusText: '演示数据 · 非真实信号',
      connected: false,
      drones: list
    });
  },

  connect: function () {
    if (this.data.mode === 'live' || this.data.mode === 'connecting' || this.client) {
      this.closeClient();
      this.startDemo();
      return;
    }

    const room = (this.data.room || '').trim() || 'trae-demo';
    const self = this;
    this.drones = [];
    this.setData({ mode: 'connecting', statusText: '连接中…', connected: false, drones: [] });

    const client = new mqtt.Client({
      url: 'wss://broker.emqx.io:8084/mqtt',
      topic: 'rid/' + room + '/drones'
    });

    client.onStatus = function (s) {
      if (!self.alive) return;
      if (s === 'connected') {
        self.setData({ statusText: '已连接 · 等待 Android 数据…', connected: true });
      } else if (s === 'error') {
        self.setData({ statusText: '连接失败 · 需配置 socket 合法域名', connected: false });
      } else if (s === 'closed') {
        self.setData({ statusText: '连接断开，自动重试中…', connected: false });
      }
    };

    client.onMessage = function (topic, payload) {
      if (!self.alive) return;
      let obj;
      try {
        obj = JSON.parse(payload);
      } catch (e) {
        return;
      }
      if (!obj || !Array.isArray(obj.drones)) return;

      self.lastSnapshot = Date.now();
      const list = obj.drones.map(demo.toView);
      self.drones = list;
      getApp().globalData.lastDrones = list;
      self.setData({
        mode: 'live',
        drones: list,
        connected: true,
        statusText: '实时 · ' + list.length + ' 架 · ' + new Date().toLocaleTimeString()
      });
    };

    client.connect();
    this.client = client;
  },

  closeClient: function () {
    if (this.client) {
      this.client.onStatus = null;
      this.client.onMessage = null;
      this.client.close();
      this.client = null;
    }
  },

  openDetail: function (e) {
    const key = e.currentTarget.dataset.key;
    const d = (this.drones || []).find(function (x) { return x.key === key; });
    getApp().globalData.selected = d || null;
    wx.navigateTo({ url: '/pages/detail/detail' });
  }
});

function hash(str) {
  let h = 0;
  const s = String(str || '');
  for (let i = 0; i < s.length; i++) {
    h = (h * 31 + s.charCodeAt(i)) | 0;
  }
  return h;
}
