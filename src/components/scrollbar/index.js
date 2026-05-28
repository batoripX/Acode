"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
require("./style.scss");
var html_tag_js_1 = require("html-tag-js");
function ScrollBar(options) {
    if (!(options === null || options === void 0 ? void 0 : options.parent))
        throw new Error("ScrollBar: Parent element required.");
    var _a = options.placement, placement = _a === void 0 ? "right" : _a;
    var isVertical = placement === "right" || placement === "left";
    var TIMEOUT = 2000;
    var $cursor = (0, html_tag_js_1.default)("span", { className: "scroll-cursor", style: { top: "0", left: "0" } });
    var $thumb = (0, html_tag_js_1.default)("span", { className: "thumb" });
    var $container = (0, html_tag_js_1.default)("div", { className: "container", children: [$cursor, $thumb] });
    var $scrollbar = (0, html_tag_js_1.default)("div", {
        className: "scrollbar-container ".concat(placement)
    });
    $scrollbar.append($container);
    var scroll = 0;
    var scrollbarSize = options.width || 20;
    var height = 0, width = 0, rect = null;
    var touchStartPos = { x: 0, y: 0 };
    var hideTimeout;
    var removeTimeout;
    var isMoving = false;
    var setWidth = function (w) {
        scrollbarSize = w;
        if (isVertical)
            $scrollbar.style.width = $cursor.style.width = "".concat(w, "px");
        else
            $scrollbar.style.height = $cursor.style.height = "".concat(w, "px");
    };
    var updatePosition = function (val) {
        scroll = Math.max(0, Math.min(1, val));
        var pos = scroll * (isVertical ? height : width);
        var pixelVal = "".concat(pos, "px");
        // Direct sync - much faster than MutationObserver
        if (isVertical) {
            $cursor.style.top = $thumb.style.top = pixelVal;
        }
        else {
            $cursor.style.left = $thumb.style.left = pixelVal;
        }
    };
    var onPointerMove = function (e) {
        if (!isMoving)
            return;
        var diff = isVertical ? touchStartPos.y - e.clientY : touchStartPos.x - e.clientX;
        touchStartPos = { x: e.clientX, y: e.clientY };
        var currentPos = isVertical ? parseFloat($cursor.style.top) : parseFloat($cursor.style.left);
        var newScroll = (currentPos - diff) / (isVertical ? height : width);
        updatePosition(newScroll);
        if (options.onscroll)
            options.onscroll(scroll);
    };
    var onPointerUp = function () {
        isMoving = false;
        $scrollbar.classList.remove("active");
        window.removeEventListener("pointermove", onPointerMove);
        window.removeEventListener("pointerup", onPointerUp);
        if (options.onscrollend)
            options.onscrollend();
        hideTimeout = setTimeout($scrollbar.hide, TIMEOUT);
    };
    $thumb.addEventListener("pointerdown", function (e) {
        e.preventDefault();
        isMoving = true;
        if (!rect)
            $scrollbar.resize();
        touchStartPos = { x: e.clientX, y: e.clientY };
        $scrollbar.classList.add("active");
        clearTimeout(hideTimeout);
        window.addEventListener("pointermove", onPointerMove);
        window.addEventListener("pointerup", onPointerUp);
    });
    $scrollbar.resize = function (render) {
        if (render === void 0) { render = true; }
        rect = $scrollbar.getBoundingClientRect();
        height = Math.max(0, rect.height - 20);
        width = Math.max(0, rect.width - 20);
        if (render && height && width)
            updatePosition(scroll);
    };
    $scrollbar.show = function () {
        if ($scrollbar.dataset.hidden === "false")
            return;
        $scrollbar.dataset.hidden = "false";
        clearTimeout(hideTimeout);
        clearTimeout(removeTimeout);
        $scrollbar.classList.remove("hide");
        if (!$scrollbar.isConnected)
            options.parent.append($scrollbar);
        if ($scrollbar.onshow)
            $scrollbar.onshow();
    };
    $scrollbar.hide = function () {
        if (isMoving)
            return;
        $scrollbar.dataset.hidden = "true";
        $scrollbar.classList.add("hide");
        removeTimeout = setTimeout(function () { return $scrollbar.remove(); }, 300);
        if ($scrollbar.onhide)
            $scrollbar.onhide();
    };
    // Clean API mapping
    Object.defineProperties($scrollbar, {
        value: { get: function () { return scroll; }, set: updatePosition },
        size: { get: function () { return scrollbarSize; }, set: setWidth },
        visible: { get: function () { return $scrollbar.dataset.hidden !== "true"; } },
        destroy: { value: function () { window.removeEventListener("resize", function () { return $scrollbar.resize(); }); $scrollbar.remove(); } }
    });
    setWidth(scrollbarSize);
    window.addEventListener("resize", function () { return $scrollbar.resize(); });
    return $scrollbar;
}
exports.default = ScrollBar;
