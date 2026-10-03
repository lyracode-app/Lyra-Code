/* Lyra chart renderer. Model-authored values are parsed as data, never evaluated. */
(function () {
  'use strict';
  var host = document.getElementById('chart');
  var viewport = document.getElementById('chart-viewport');
  var payload = JSON.parse(document.getElementById('chart-data').textContent);
  var chart = null;
  var ready = false;
  var background = payload.dark ? '#1c1b1f' : '#ffffff';
  var diagramSize = null;

  function reportError(error) {
    var message = String(error && error.message || error).slice(0, 2000);
    host.textContent = message;
    if (window.LyraChart) window.LyraChart.failed(message);
  }

  function svgText() {
    var original = host.querySelector('svg');
    if (!original) throw new Error('Chart has not finished rendering.');
    var svg = original.cloneNode(true);
    svg.setAttribute('xmlns', 'http://www.w3.org/2000/svg');
    // Exports must not depend on the WebView document or external resources.
    svg.querySelectorAll('script,foreignObject,a').forEach(function (element) {
      if (element.tagName.toLowerCase() === 'a') element.replaceWith.apply(element, Array.from(element.childNodes));
      else element.remove();
    });
    svg.querySelectorAll('*').forEach(function (element) {
      Array.from(element.attributes).forEach(function (attribute) {
        var name = attribute.name.toLowerCase();
        if (name.startsWith('on') || ((name === 'href' || name === 'xlink:href') && !attribute.value.startsWith('#'))) {
          element.removeAttribute(attribute.name);
        }
      });
    });
    var rect = original.getBoundingClientRect();
    var box = original.viewBox && original.viewBox.baseVal;
    var width = box && box.width || parseFloat(original.getAttribute('width')) || rect.width || 800;
    var height = box && box.height || parseFloat(original.getAttribute('height')) || rect.height || 400;
    svg.setAttribute('width', width);
    svg.setAttribute('height', height);
    svg.style.maxWidth = 'none';
    var fill = document.createElementNS('http://www.w3.org/2000/svg', 'rect');
    fill.setAttribute('x', box && box.x || 0);
    fill.setAttribute('y', box && box.y || 0);
    fill.setAttribute('width', width);
    fill.setAttribute('height', height);
    fill.setAttribute('fill', background);
    svg.insertBefore(fill, svg.firstChild);
    return { text: new XMLSerializer().serializeToString(svg), width: width, height: height };
  }

  function reportReady() {
    ready = true;
    if (window.LyraChart) window.LyraChart.ready(viewport.getBoundingClientRect().height);
  }

  // Inline cards show the entire diagram in a compact viewport. Keep the
  // underlying SVG at its natural size so exports retain their resolution.
  function layoutChart() {
    var availableWidth = Math.max(1, viewport.clientWidth);
    if (payload.fullscreen) {
      if (chart) {
        host.style.width = availableWidth + 'px';
        host.style.height = Math.max(1, window.innerHeight) + 'px';
        chart.resize();
      }
    } else {
      var width = diagramSize ? diagramSize.width + 24 : Math.max(600, availableWidth);
      var height = diagramSize ? diagramSize.height + 24 : 360;
      host.style.width = width + 'px';
      host.style.height = height + 'px';
      if (chart) chart.resize();
      var scale = Math.min(1, availableWidth / width, 240 / height);
      var previewHeight = Math.max(96, height * scale);
      viewport.style.height = previewHeight + 'px';
      viewport.style.overflow = 'hidden';
      host.style.position = 'absolute';
      host.style.left = (availableWidth - width * scale) / 2 + 'px';
      host.style.top = (previewHeight - height * scale) / 2 + 'px';
      host.style.transformOrigin = 'top left';
      host.style.transform = 'scale(' + scale + ')';
    }
    if (diagramSize) improveDiagramLabels(host.querySelector('svg'));
    reportReady();
  }

  function improveDiagramLabels(svg) {
    if (!svg) return;
    // Gantt's automatic time ticks can repeat dates and overlap on a phone.
    var right = -Infinity;
    var previous = '';
    svg.querySelectorAll('.grid .tick text').forEach(function (text) {
      text.style.display = '';
      var rect = text.getBoundingClientRect();
      var label = text.textContent;
      if (label === previous || rect.left < right + 6) text.style.display = 'none';
      else { right = rect.right; previous = label; }
    });
    // Mermaid's SVG-only circular mind-map labels start at the circle's center.
    // Center their measured bounds, retaining the library's vertical placement.
    svg.querySelectorAll('.mindmap-node').forEach(function (node) {
      var circle = node.querySelector('circle.label-container');
      var label = node.querySelector('g.label');
      if (circle && label) {
        var box = label.getBBox();
        var transform = label.transform.baseVal.consolidate();
        var y = transform ? transform.matrix.f : 0;
        label.setAttribute('transform', 'translate(' + (circle.cx.baseVal.value - box.x - box.width / 2) + ',' + y + ')');
      }
      if (node.classList.contains('section-root')) {
        if (circle) circle.style.fill = payload.dark ? '#374151' : '#e8e7ff';
        node.querySelectorAll('text,tspan').forEach(function (text) {
          text.style.fill = payload.dark ? '#f3f4f6' : '#202124';
        });
      }
    });
  }

  // Called only by the native save action. Large exports cross the bridge once.
  window.lyraExport = async function (format) {
    try {
      if (!ready) throw new Error('Chart has not finished rendering.');
      var image = svgText();
      if (format === 'svg') {
        window.LyraChart.exported('svg', image.text);
        return;
      }
      if (format !== 'png') throw new Error('Unsupported export format.');
      var scale = Math.min(2, 4096 / Math.max(image.width, image.height), Math.sqrt(16000000 / (image.width * image.height)));
      var canvas = document.createElement('canvas');
      canvas.width = Math.max(1, Math.round(image.width * scale));
      canvas.height = Math.max(1, Math.round(image.height * scale));
      var url = URL.createObjectURL(new Blob([image.text], { type: 'image/svg+xml;charset=utf-8' }));
      try {
        var img = new Image();
        await new Promise(function (resolve, reject) { img.onload = resolve; img.onerror = reject; img.src = url; });
        var context = canvas.getContext('2d');
        context.fillStyle = background;
        context.fillRect(0, 0, canvas.width, canvas.height);
        context.drawImage(img, 0, 0, canvas.width, canvas.height);
        window.LyraChart.exported('png', canvas.toDataURL('image/png').split(',')[1]);
      } finally { URL.revokeObjectURL(url); }
    } catch (error) {
      if (window.LyraChart) window.LyraChart.exportFailed(String(error && error.message || error));
    }
  };

  async function render() {
    if (payload.engine === 'mermaid') {
      mermaid.initialize({
        startOnLoad: false, securityLevel: 'strict', suppressErrorRendering: true,
        theme: payload.dark ? 'dark' : 'default', fontFamily: 'sans-serif', htmlLabels: false,
        maxTextSize: 100000, maxEdges: 500,
        flowchart: { htmlLabels: false, useMaxWidth: true },
        secure: ['secure', 'securityLevel', 'startOnLoad', 'maxTextSize', 'maxEdges', 'htmlLabels', 'suppressErrorRendering']
      });
      var result = await mermaid.render('lyra-diagram', payload.source);
      host.innerHTML = result.svg;
      host.querySelectorAll('a').forEach(function (element) { element.removeAttribute('href'); element.removeAttribute('xlink:href'); });
      var svg = host.querySelector('svg');
      var box = svg && svg.viewBox.baseVal;
      if (box && box.width > 0 && box.height > 0) {
        diagramSize = { width: box.width, height: box.height };
        svg.style.width = payload.fullscreen ? '100%' : box.width + 'px';
        svg.style.height = payload.fullscreen ? 'auto' : box.height + 'px';
        svg.style.minWidth = payload.fullscreen ? Math.min(box.width, 1400) + 'px' : '0';
        if (!payload.fullscreen) svg.style.maxWidth = 'none';
      }
      layoutChart();
      window.addEventListener('resize', layoutChart);
      return;
    }
    var option = JSON.parse(payload.source);
    option.animation = false;
    option.backgroundColor = background;
    // Tooltips use SVG text; AI-provided labels cannot become HTML.
    function textTooltip(value) {
      if (Array.isArray(value)) return value.forEach(textTooltip);
      if (value && typeof value === 'object') {
        Object.keys(value).forEach(function (key) {
          if (key === 'tooltip' && value[key] && typeof value[key] === 'object') {
            value[key].renderMode = 'richText';
            delete value[key].formatter;
          }
          textTooltip(value[key]);
        });
      }
    }
    textTooltip(option);
    option.tooltip = Object.assign({}, option.tooltip, { renderMode: 'richText' });
    host.style.width = (payload.fullscreen ? viewport.clientWidth : Math.max(600, viewport.clientWidth)) + 'px';
    host.style.height = (payload.fullscreen ? Math.max(1, window.innerHeight) : 360) + 'px';
    host.style.padding = '0';
    chart = echarts.init(host, payload.dark ? 'dark' : null, { renderer: 'svg' });
    chart.setOption(option);
    layoutChart();
    window.addEventListener('resize', layoutChart);
  }
  render().catch(reportError);
})();
