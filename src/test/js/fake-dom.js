/*
 * Minimal, zero-dependency DOM stub for exercising the Control_Tower front-end
 * (src/main/resources/static/app.js) under Node's built-in test runner.
 *
 * It implements only the surface app.js touches: document.getElementById,
 * document.createElement, document.querySelectorAll, and element node behavior
 * (className / textContent / innerHTML / setAttribute / appendChild /
 * addEventListener + a helper to fire events). No external dependency or network
 * install is required, so these tests run anywhere Node is present.
 */
"use strict";

class FakeElement {
  constructor(tagName) {
    this.tagName = String(tagName || "div").toUpperCase();
    this.className = "";
    this.children = [];
    this.attributes = {};
    this.listeners = {};
    this._text = "";
    this.parentNode = null;
    // Arbitrary properties (e.g. `type`, `value`) are settable directly.
  }

  get textContent() {
    if (this.children.length === 0) return this._text;
    return this.children.map(function (c) { return c.textContent; }).join("");
  }
  set textContent(v) {
    this._text = v == null ? "" : String(v);
    this.children = [];
  }

  // Only ever assigned "" by app.js to clear a container.
  get innerHTML() { return ""; }
  set innerHTML(v) {
    this.children = [];
    this._text = "";
  }

  appendChild(node) {
    node.parentNode = this;
    this.children.push(node);
    return node;
  }

  setAttribute(name, value) { this.attributes[name] = String(value); }
  getAttribute(name) {
    return Object.prototype.hasOwnProperty.call(this.attributes, name)
      ? this.attributes[name] : null;
  }

  addEventListener(type, handler) {
    (this.listeners[type] = this.listeners[type] || []).push(handler);
  }

  // Test helper: invoke registered listeners for an event type.
  fire(type) {
    (this.listeners[type] || []).forEach(function (h) { h({ type: type }); });
  }

  // querySelectorAll on elements is unused by the panels under test.
  querySelectorAll() { return []; }
}

class FakeDocument {
  constructor(ids) {
    this.byId = {};
    (ids || []).forEach(function (id) {
      // caller populates via registerId; keep a placeholder container per id.
    }, this);
  }

  registerId(id, className) {
    var node = new FakeElement("div");
    node.setAttribute("id", id);
    if (className) node.className = className;
    this.byId[id] = node;
    return node;
  }

  getElementById(id) {
    return Object.prototype.hasOwnProperty.call(this.byId, id) ? this.byId[id] : null;
  }

  createElement(tag) { return new FakeElement(tag); }

  querySelectorAll() { return []; }
}

/** Builds a document exposing containers for the given element ids. */
function makeDocument(ids) {
  var doc = new FakeDocument();
  (ids || []).forEach(function (id) { doc.registerId(id); });
  return doc;
}

/** Depth-first collection of nodes whose className contains `cls`. */
function findAllByClass(node, cls) {
  var out = [];
  (function walk(n) {
    if (!n) return;
    if (n.className && n.className.split(/\s+/).indexOf(cls) !== -1) out.push(n);
    (n.children || []).forEach(walk);
  })(node);
  return out;
}

/** All descendant nodes (excluding the root) as a flat list. */
function allDescendants(node) {
  var out = [];
  (function walk(n) {
    (n.children || []).forEach(function (c) { out.push(c); walk(c); });
  })(node);
  return out;
}

module.exports = { FakeElement, FakeDocument, makeDocument, findAllByClass, allDescendants };
