/*
 * Based on GitHub Collapse Markdown
 * https://github.com/Mottie/GitHub-userscripts/master/github-collapse-markdown.user.js
 *
 * Modified for collapsing header hierarchically and to run with Markdown Navigator JavaFX WebView
 * JSBridge & Helper
*/

// noinspection ES6ConvertVarToLetConst
var markdownNavigator;
// noinspection ES6ConvertVarToLetConst
var firstCollapsedHeading;

(function () {
    "use strict";

    const COLLAPSED_STATE = "githubCollapsedHeadingsState";
    // noinspection BadExpressionStatementJS
    const defaultColors = [
        // palette generated by http://tools.medialab.sciences-po.fr/iwanthue/
        // (colorblind friendly, soft)
        "#6778d0", "#ac9c3d", "#b94a73", "#56ae6c", "#9750a1", "#ba543d"
    ];
    const HEADERS = "H1 H2 H3 H4 H5 H6".split(" ");
    const COLLAPSED = "ghcm-collapsed";
    const HIDDEN = "ghcm-hidden";
    const ARROW_COLORS = document.createElement("style");

    let headingIndex = 0;
    let usedHeadings = [];      // track which heading ids are still in use

    // our stored state information
    let headingsMap = {};      // hash map of heading id to index
    let collapsedHeadings = {};

    let colors = defaultColors;

    function addColors() {
        ARROW_COLORS.textContent = `
.markdown-body h1:after, .markdown-format h1:after { color:${colors[0]} }
.markdown-body h2:after, .markdown-format h2:after { color:${colors[1]} }
.markdown-body h3:after, .markdown-format h3:after { color:${colors[2]} }
.markdown-body h4:after, .markdown-format h4:after { color:${colors[3]} }
.markdown-body h5:after, .markdown-format h5:after { color:${colors[4]} }
.markdown-body h6:after, .markdown-format h6:after { color:${colors[5]} }
    `;
    }

    function adjustHeadingIndex() {
        headingIndex = 0;
        for (let id in headingsMap) {
            if (!headingsMap.hasOwnProperty(id)) {
                continue;
            }
            if (headingIndex < headingsMap[id]) {
                headingIndex = headingsMap[id];
            }
        }
    }

    function getHeaderIndex(id) {
        usedHeadings[id] = 1;

        if (headingsMap.hasOwnProperty(id)) {
            return headingsMap[id];
        }
        headingsMap[id] = ++headingIndex;
        return headingIndex;
    }

    function saveCollapsedState() {
        // clean out unused headings
        let newMap = {};
        for (let id in headingsMap) {
            // if not collapsed, we can remove it since it is assumed to be not collapsed
            if (!headingsMap.hasOwnProperty(id) || !headingsMap[id]) {
                continue;
            }
            if (usedHeadings.hasOwnProperty(id)) {
                newMap[id] = headingsMap[id];
            }
        }
        headingsMap = newMap;
        adjustHeadingIndex();

        // state name must be same as the global variable, this is where it will be set on load
        let state = {
            "headingsMap": headingsMap,
            "collapsedHeadings": collapsedHeadings,
        };
        let str = JSON.stringify(state, null, 2);
        markdownNavigator.setState(COLLAPSED_STATE, state)
    }

    function loadCollapsedState() {
        let state = markdownNavigator.getState(COLLAPSED_STATE);

        if (state
            && state.hasOwnProperty("headingsMap")
            && state.hasOwnProperty("collapsedHeadings")) {
            headingsMap = state["headingsMap"];

            collapsedHeadings = state["collapsedHeadings"];
            if (collapsedHeadings.constructor === Array) {
                // arrays don't stringify
                collapsedHeadings = {};
            }

            adjustHeadingIndex();
        } else {
            headingsMap = {};
            collapsedHeadings = {};
            headingIndex = 0;
        }
    }

    function toggleHeader(el, shifted) {
        if (el) {
            el.classList.toggle(COLLAPSED);
            let els;
            const name = el.nodeName || "",
                level = parseInt(name.replace(/[^\d]/, ""), 10),
                isCollapsed = el.classList.contains(COLLAPSED);

            if (shifted) {
                // collapse all same level anchors
                els = $$(`.markdown-body ${name}, .wiki-body ${name}, .markdown-format ${name}`);
                for (el of els) {
                    nextHeader(el, level, isCollapsed);
                }
            } else {
                nextHeader(el, level, isCollapsed);
            }
            removeSelection();
        }
    }

    function headerIds(el) {
        const level = parseInt((el.nodeName || "").replace(/[^\d]/, ""), 10);
        let selector = HEADERS.slice(0, level).join(",");

        const headers = [];
        const orig = el;
        while (el && el.nodeName !== "BODY") {
            try {
                if (el && el.nodeName !== "#comment" && el.nodeName !== "#text" && el.matches(selector)) {
                    if (el.id) {
                        headers.push(el.nodeName + "-" + getHeaderIndex(el.id));
                    } else {
                        headers.push(el.nodeName + "-" + "undef");
                    }

                    const level = parseInt((el.nodeName || "").replace(/[^\d]/, ""), 10);
                    if (level === 1) {
                        break;
                    }
                    selector = HEADERS.slice(0, level - 1).join(",");
                }
            }
            catch (e) {
                console.error("headerIds exception: " + e);
            }

            let previous = el.previousSibling;
            if (previous) {
                el = previous;
            } else {
                el = el.parentNode
            }
        }

        // get the nested header id signature
        return headers.reverse().join("-");
    }

    function nextHeader(el, level, isCollapsed) {
        el.classList[isCollapsed ? "add" : "remove"](COLLAPSED);

        let ids = headerIds(el);
        collapsedHeadings[ids] = isCollapsed;

        const selector = HEADERS.slice(0, level).join(","),
            name = [COLLAPSED, HIDDEN],
            els = [];
        el = el.nextElementSibling;

        while (el && !el.matches(selector)) {
            els[els.length] = el;
            el = el.nextElementSibling;
        }
        if (els.length) {
            const headers = HEADERS.join(",");
            let currentCollapsed = isCollapsed;

            if (isCollapsed) {
                els.forEach(el => {
                    el.classList.add(HIDDEN);
                })
            } else {
                els.forEach(el => {
                    if (el.matches(headers)) {
                        currentCollapsed = el.classList.contains(COLLAPSED);
                        el.classList.remove(HIDDEN);
                    } else {
                        if (!currentCollapsed) {
                            el.classList.remove(HIDDEN);
                        }
                    }
                })
            }
        }
    }

    // callback for scroll highlight
    firstCollapsedHeading = function (element) {
        if (element.classList.contains(HIDDEN)) {
            // find first previous heading which is collapsed and not hidden
            let selector = HEADERS.join(",");
            let el = element.previousElementSibling;
            while (el) {
                if (el.matches(selector) && !el.classList.contains(HIDDEN) && el.classList.contains(COLLAPSED)) {
                    // have our heading, need to check if it is in a collapsed detail tag
                    let parentElem = el.parentElement;
                    let lastDetails = null;
                    while (parentElem) {
                        if (parentElem.tagName === "DETAILS" && !parentElem.attributes.hasOwnProperty("open")) {
                            // this one will be it, unless it has a parent details tag
                            lastDetails = parentElem;
                        }
                        parentElem = parentElem.parentElement
                    }
                    if (lastDetails) {
                        return lastDetails;
                    }
                    return el;
                }
                el = el.previousElementSibling;
            }
        }
        return null;
    };

    // show siblings of hash target
    function siblings(target) {
        let el = target.nextElementSibling,
            els = [target];
        const level = parseInt((target.nodeName || "").replace(/[^\d]/, ""), 10),
            selector = HEADERS.slice(0, level - 1).join(",");
        while (el && !el.matches(selector)) {
            els[els.length] = el;
            el = el.nextElementSibling;
        }
        el = target.previousElementSibling;
        while (el && !el.matches(selector)) {
            els[els.length] = el;
            el = el.previousElementSibling;
        }
        if (els.length) {
            els = els.filter(el => {
                return el.nodeName === target.nodeName;
            });
            els.classList.remove(HIDDEN);
        }
        nextHeader(target, level, false);
    }

    function removeSelection() {
        // remove text selection - http://stackoverflow.com/a/3171348/145346
        const sel = window.getSelection ? window.getSelection() : document.selection;
        if (sel) {
            if (sel.removeAllRanges) {
                sel.removeAllRanges();
            } else {
                if (sel.empty) {
                    sel.empty();
                }
            }
        }
    }

    function addBinding() {
        document.addEventListener("click", event => {
            try {
                let target = event.target;
                console.debug("onClick GitHub Collapse: target:" + target + ", shifted: " + event.shiftKey);

                const name = (target && (target.nodeName || "")).toLowerCase();
                if (name === "path") {
                    target = closest(target, "svg");
                }

                if (!target || target.classList.contains("anchor") || name === "a" || name === "img" ||
                    // add support for "pointer-events:none" applied to "anchor" in
                    // https://github.com/StylishThemes/GitHub-FixedHeader
                    target.classList.contains("octicon-link")) {
                    return;
                }

                // check if element is inside a header
                target = closest(event.target, HEADERS.join(","));
                if (target && HEADERS.indexOf(target.nodeName || "") > -1) {
                    // make sure the header is inside of markdown
                    if (closest(target, ".markdown-body, .wiki-body, .markdown-format")) {
                        event.preventDefault();
                        event.stopPropagation();
                        markdownNavigator.setEventHandledBy("GitHubCollapse"); // the above only work for within JS, java events still fire
                        toggleHeader(target, event.shiftKey);
                        saveCollapsedState();
                    }
                }
            }
            catch (e) {
                let tmp = 0;
            }
        });
    }

    function checkHash() {
        let el, els, md;
        const mds = $$(".markdown-body, .wiki-body, .markdown-format "),
            tmp = (window.location.hash || "").replace(/#/, "");

        for (md of mds) {
            els = $$(HEADERS.join(","), md);
            if (els.length > 1) {
                for (el of els) {
                    let ids = headerIds(el);
                    let isCollapsed = collapsedHeadings.hasOwnProperty(ids) ? collapsedHeadings[ids] : false;
                    if (el && isCollapsed && !el.classList.contains(COLLAPSED)) {
                        toggleHeader(el, false);
                    }
                }
            }
        }

        // open up
        if (tmp) {
            els = $(`#user-content-${tmp}`);
            if (els && els.classList.contains("anchor")) {
                el = els.parentNode;
                if (el.matches(HEADERS.join(","))) {
                    siblings(el);
                }
            }
        }
    }

    function checkColors() {
        if (!colors || colors.length !== 6) {
            colors = [].concat(defaultColors);
        }
    }

    function $(selector, el) {
        return (el || document).querySelector(selector);
    }

    function $$(selectors, el) {
        return Array.from((el || document).querySelectorAll(selectors));
    }

    function closest(el, selector) {
        while (el && el.nodeName !== "BODY" && !el.matches(selector)) {
            el = el.parentNode;
        }
        return el && el.matches(selector) ? el : null;
    }

    // do this right away to collapse the headers if we have the state information
    document.querySelector("head").appendChild(ARROW_COLORS);
    loadCollapsedState();

    checkColors();
    addColors();
    addBinding();
    checkHash();
})();

