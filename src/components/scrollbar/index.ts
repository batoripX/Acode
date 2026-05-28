import "./style.scss";
import tag from "html-tag-js";

interface ScrollbarOptions {
    parent: HTMLElement;
    placement?: "top" | "left" | "right" | "bottom";
    width?: number;
    onscroll?: (val: number) => void;
    onscrollend?: () => void;
}

export interface ScrollbarElement extends HTMLDivElement {
    destroy: () => void;
    render: () => void;
    show: () => void;
    hide: () => void;
    resize: (render?: boolean) => void;
    hideImmediately: () => void;
    value: number;
    size: number;
    visible: boolean;
    onshow?: () => void;
    onhide?: () => void;
}

export default function ScrollBar(options: ScrollbarOptions): ScrollbarElement {
    if (!options?.parent) throw new Error("ScrollBar: Parent element required.");

    const { placement = "right" } = options;
    const isVertical = placement === "right" || placement === "left";
    const TIMEOUT = 2000;

    const $cursor = tag("span", { className: "scroll-cursor", style: { top: "0", left: "0" } });
    const $thumb = tag("span", { className: "thumb" });
    const $container = tag("div", { className: "container", children: [$cursor, $thumb] });
    const $scrollbar = tag("div", { 
        className: `scrollbar-container ${placement}` 
    }) as ScrollbarElement;

    $scrollbar.append($container);

    let scroll = 0;
    let scrollbarSize = options.width || 20;
    let height = 0, width = 0, rect: DOMRect | null = null;
    let touchStartPos = { x: 0, y: 0 };
    let hideTimeout: ReturnType<typeof setTimeout>;
    let removeTimeout: ReturnType<typeof setTimeout>;
    let isMoving = false;

    const setWidth = (w: number) => {
        scrollbarSize = w;
        if (isVertical) $scrollbar.style.width = $cursor.style.width = `${w}px`;
        else $scrollbar.style.height = $cursor.style.height = `${w}px`;
    };

    const updatePosition = (val: number) => {
        scroll = Math.max(0, Math.min(1, val));
        const pos = scroll * (isVertical ? height : width);
        const pixelVal = `${pos}px`;
        
        // Direct sync - much faster than MutationObserver
        if (isVertical) {
            $cursor.style.top = $thumb.style.top = pixelVal;
        } else {
            $cursor.style.left = $thumb.style.left = pixelVal;
        }
    };

    const onPointerMove = (e: PointerEvent) => {
        if (!isMoving) return;
        const diff = isVertical ? touchStartPos.y - e.clientY : touchStartPos.x - e.clientX;
        touchStartPos = { x: e.clientX, y: e.clientY };

        const currentPos = isVertical ? parseFloat($cursor.style.top) : parseFloat($cursor.style.left);
        const newScroll = (currentPos - diff) / (isVertical ? height : width);
        
        updatePosition(newScroll);
        if (options.onscroll) options.onscroll(scroll);
    };

    const onPointerUp = () => {
        isMoving = false;
        $scrollbar.classList.remove("active");
        window.removeEventListener("pointermove", onPointerMove);
        window.removeEventListener("pointerup", onPointerUp);
        if (options.onscrollend) options.onscrollend();
        hideTimeout = setTimeout($scrollbar.hide, TIMEOUT);
    };

    $thumb.addEventListener("pointerdown", (e: PointerEvent) => {
        e.preventDefault();
        isMoving = true;
        if (!rect) $scrollbar.resize();
        touchStartPos = { x: e.clientX, y: e.clientY };
        $scrollbar.classList.add("active");
        clearTimeout(hideTimeout);
        
        window.addEventListener("pointermove", onPointerMove);
        window.addEventListener("pointerup", onPointerUp);
    });

    $scrollbar.resize = (render = true) => {
        rect = $scrollbar.getBoundingClientRect();
        height = Math.max(0, rect.height - 20);
        width = Math.max(0, rect.width - 20);
        if (render && height && width) updatePosition(scroll);
    };

    $scrollbar.show = () => {
        if ($scrollbar.dataset.hidden === "false") return;
        $scrollbar.dataset.hidden = "false";
        clearTimeout(hideTimeout);
        clearTimeout(removeTimeout);
        $scrollbar.classList.remove("hide");
        if (!$scrollbar.isConnected) options.parent.append($scrollbar);
        if ($scrollbar.onshow) $scrollbar.onshow();
    };

    $scrollbar.hide = () => {
        if (isMoving) return;
        $scrollbar.dataset.hidden = "true";
        $scrollbar.classList.add("hide");
        removeTimeout = setTimeout(() => $scrollbar.remove(), 300);
        if ($scrollbar.onhide) $scrollbar.onhide();
    };

    // Clean API mapping
    Object.defineProperties($scrollbar, {
        value: { get: () => scroll, set: updatePosition },
        size: { get: () => scrollbarSize, set: setWidth },
        visible: { get: () => $scrollbar.dataset.hidden !== "true" },
        destroy: { value: () => { window.removeEventListener("resize", () => $scrollbar.resize()); $scrollbar.remove(); } }
    });

    setWidth(scrollbarSize);
    window.addEventListener("resize", () => $scrollbar.resize());

    return $scrollbar;
}
