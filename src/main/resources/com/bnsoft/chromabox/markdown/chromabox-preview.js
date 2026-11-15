// Chromabox swatches in the Markdown preview (C-07). The IDE finds the colors; this only puts a swatch before each
// text it was given, in text nodes, and never changes the text itself.
//
// The preview updates the page with incremental-dom, which expects the nodes it made. So each update first undoes
// the swatches exactly: it removes them and joins the text nodes they split back into the node incremental-dom made.
// After the update, they're put back.
(function () {
  "use strict";
  const SWATCH = "chromabox-swatch";
  let swatches = new Map();
  let pattern = null;
  let last = "";
  // The swatches shown, and the text nodes made by splitting, in document order.
  let shown = [];
  let pieces = [];

  function escape(text) {
    return text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  }

  function receive(json) {
    if (json === last) return;
    last = json;
    swatches = new Map(JSON.parse(json).swatches.map(s => [s.text, s]));
    // Longest first, so that a gradient wins over a color inside it, and #ffffff over #fff.
    const texts = [...swatches.keys()].sort((a, b) => b.length - a.length);
    pattern = texts.length === 0 ? null : new RegExp("(?<![\\w#-])(?:" + texts.map(escape).join("|") + ")(?![\\w-])", "g");
    undo();
    decorate();
  }

  function undo() {
    for (const swatch of shown) swatch.remove();
    for (const piece of pieces) {
      const before = piece.previousSibling;
      if (before !== null && before.nodeType === Node.TEXT_NODE) {
        before.appendData(piece.data);
        piece.remove();
      }
    }
    shown = [];
    pieces = [];
  }

  function decorate() {
    if (pattern === null || !document.body) return;
    const nodes = [];
    const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, {
      acceptNode(node) {
        const parent = node.parentElement;
        if (parent === null || parent.closest("script, style, textarea, ." + SWATCH)) return NodeFilter.FILTER_REJECT;
        return NodeFilter.FILTER_ACCEPT;
      },
    });
    while (walker.nextNode()) nodes.push(walker.currentNode);
    for (const node of nodes) decorateNode(node);
  }

  function decorateNode(node) {
    const text = node.data;
    pattern.lastIndex = 0;
    const found = [];
    let match;
    while ((match = pattern.exec(text)) !== null) found.push(match);
    const made = [];
    // From the end, so that the node incremental-dom made keeps the start of the text and stays where it was.
    for (let i = found.length - 1; i >= 0; i--) {
      const literal = found[i].index === 0 ? node : node.splitText(found[i].index);
      if (literal !== node) made.unshift(literal);
      const swatch = swatchFor(swatches.get(found[i][0]));
      literal.parentNode.insertBefore(swatch, literal);
      shown.push(swatch);
    }
    pieces.push(...made);
  }

  function swatchFor(swatch) {
    const outer = document.createElement("span");
    outer.className = swatch.wide ? SWATCH + " " + SWATCH + "-wide" : SWATCH;
    outer.setAttribute("aria-hidden", "true");
    const fill = document.createElement("span");
    fill.style.setProperty("background-image", swatch.background);
    outer.appendChild(fill);
    return outer;
  }

  function request() {
    try {
      window.__IntelliJTools.messagePipe.post("chromabox/request", "");
    } catch (e) {
      console.error(e);
    }
  }

  window.__IntelliJTools.messagePipe.subscribe("chromabox/swatches", receive);
  const patch = IncrementalDOM.patch;
  IncrementalDOM.patch = function () {
    undo();
    return patch.apply(this, arguments);
  };
  IncrementalDOM.notifications.afterPatchListeners.push(() => {
    // Put back the swatches known, then ask for the document's current ones.
    decorate();
    request();
  });
  request();
})();
