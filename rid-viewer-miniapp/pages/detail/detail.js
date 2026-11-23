Page({
  data: {
    title: '未知 ID',
    statusText: '',
    dotColor: '#F59E0B',
    rows: []
  },

  onLoad: function () {
    const app = getApp();
    const d = app.globalData.selected || {};
    const rows = [];
    const push = function (k, v) {
      if (v !== undefined && v !== null && v !== '' && v !== '—') {
        rows.push({ k: k, v: String(v) });
      }
    };
    const num = function (v, n) {
      return typeof v === 'number' ? v.toFixed(n) : null;
    };

    push('机型', d.uaTypeText);
    push('状态', d.statusText);
    push('信号来源', d.source === 'WIFI' ? 'Wi-Fi Beacon' : '蓝牙广播');
    push('信号强度', d.rssi !== undefined ? d.rssi + ' dBm' : null);
    push('距离', d.distText);
    push('方位', typeof d.bearing === 'number' ? Math.round(d.bearing) + '°' : null);
    push('经纬度', (typeof d.lat === 'number' && typeof d.lon === 'number')
      ? d.lat.toFixed(6) + ', ' + d.lon.toFixed(6)
      : null);
    push('大地高度', typeof d.altGeo === 'number' ? Math.round(d.altGeo) + ' m' : null);
    push('相对高度', typeof d.height === 'number' ? Math.round(d.height) + ' m' : null);
    push('水平速度', num(d.speedH, 1) !== null ? num(d.speedH, 1) + ' m/s' : null);
    push('垂直速度', num(d.speedV, 1) !== null ? num(d.speedV, 1) + ' m/s' : null);
    push('航向', typeof d.direction === 'number' ? d.direction + '°' : null);
    push('操控者 ID', d.operatorId);
    push('自我描述', d.selfId);

    this.setData({
      title: d.uasId || '未知 ID',
      statusText: d.statusText || '',
      dotColor: d.dotColor || '#F59E0B',
      rows: rows
    });
  }
});
