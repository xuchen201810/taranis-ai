/**
 * 演示数据 + 列表展示字段映射。
 * 演示数据仅在“演示”模式下使用，界面会明确标注。
 */

function statusColor(status) {
  if (status === 2) return '#22C55E';
  if (status === 3 || status === 4) return '#EF4444';
  if (status === 1) return '#3B82F6';
  return '#F59E0B';
}

function fmtDistance(m) {
  if (m === null || m === undefined) return null;
  return m < 1000 ? Math.round(m) + ' m' : (m / 1000).toFixed(2) + ' km';
}

/** 把后端/演示数据转成列表可用字段 */
function toView(d) {
  d = d || {};
  var dist = fmtDistance(d.distanceM);
  var alt = d.altGeo !== undefined && d.altGeo !== null ? d.altGeo
    : (d.height !== undefined && d.height !== null ? d.height : null);
  var speed = typeof d.speedH === 'number' ? d.speedH.toFixed(1) : '0.0';

  return {
    key: d.key || d.uasId || ('k' + Math.random()),
    uasId: d.uasId || '',
    idText: d.uasId || '未知 ID',
    sub: [
      d.uaTypeText || '',
      d.source === 'WIFI' ? 'Wi-Fi' : (d.source === 'BLE' ? 'BLE' : ''),
      d.statusText || ''
    ].filter(function (x) { return !!x; }).join(' · '),
    distText: dist || (d.rssi !== undefined ? d.rssi + ' dBm' : '—'),
    metaText: speed + ' m/s · ' + (alt !== null ? Math.round(alt) + ' m' : '高度未知'),
    dotColor: statusColor(d.status),
    distanceM: d.distanceM === undefined ? null : d.distanceM,
    bearing: d.bearing === undefined ? null : d.bearing,
    rssi: d.rssi === undefined ? -70 : d.rssi,
    status: d.status,
    statusText: d.statusText || '',
    uaTypeText: d.uaTypeText || '',
    source: d.source || 'BLE',
    lat: d.lat,
    lon: d.lon,
    altGeo: d.altGeo,
    height: d.height,
    speedH: d.speedH,
    speedV: d.speedV,
    direction: d.direction,
    operatorLat: d.operatorLat,
    operatorLon: d.operatorLon,
    operatorId: d.operatorId || '',
    selfId: d.selfId || ''
  };
}

function generate(t) {
  var base = [
    {
      key: 'demo-1',
      uasId: '1581F5BK-DEMO-0001',
      uaTypeText: '多旋翼',
      status: 2,
      statusText: '空中',
      lat: 31.230416,
      lon: 121.473701,
      altGeo: 110 + Math.sin(t / 3) * 6,
      height: 60,
      speedH: 8.5,
      speedV: 0.2,
      direction: 90,
      rssi: -61,
      source: 'BLE',
      distanceM: 320 + Math.sin(t / 6) * 35,
      bearing: (37 + t * 2) % 360,
      operatorLat: 31.2299,
      operatorLon: 121.4731,
      operatorId: 'CN-OP-DEMO-0001',
      selfId: '电力巡检'
    },
    {
      key: 'demo-2',
      uasId: 'RID-CN-DEMO-0002',
      uaTypeText: '固定翼',
      status: 1,
      statusText: '地面',
      lat: 31.2268,
      lon: 121.4692,
      altGeo: 12,
      height: 2,
      speedH: 0.0,
      speedV: 0.0,
      direction: 210,
      rssi: -78,
      source: 'WIFI',
      distanceM: 860 + Math.cos(t / 8) * 40,
      bearing: (212 + Math.sin(t / 10) * 3) % 360,
      operatorLat: 31.2268,
      operatorLon: 121.4692,
      operatorId: 'CN-OP-DEMO-0002',
      selfId: '航拍作业'
    },
    {
      key: 'demo-3',
      uasId: 'RID-CN-DEMO-0003',
      uaTypeText: '复合翼',
      status: 3,
      statusText: '紧急',
      lat: 31.2329,
      lon: 121.4768,
      altGeo: 180 + Math.cos(t / 4) * 10,
      height: 130,
      speedH: 15.2,
      speedV: 1.2,
      direction: 305,
      rssi: -52,
      source: 'BLE',
      distanceM: 150 + Math.sin(t / 5) * 20,
      bearing: (305 + t * 1.5) % 360,
      operatorLat: 31.2325,
      operatorLon: 121.4760,
      operatorId: 'CN-OP-DEMO-0003',
      selfId: '应急救援'
    }
  ];
  return base.map(toView);
}

module.exports = {
  toView: toView,
  generate: generate,
  statusColor: statusColor
};
