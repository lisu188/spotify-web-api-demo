/*
 * Minimal jQuery-compatible helper for HtmlUnit UI workflow tests.
 */
(function (window) {
  function wrap(elements) {
    var list = elements || [];

    function each(callback) {
      for (var i = 0; i < list.length; i += 1) {
        callback(list[i], i);
      }
      return api;
    }

    function setAttr(element, name, value) {
      if (name === "class") {
        element.className = value;
        return;
      }
      if (name in element) {
        element[name] = value;
      } else {
        element.setAttribute(name, value);
      }
    }

    var api = {
      appendTo: function (targetSelector) {
        var target = window.document.querySelector(targetSelector);
        if (!target) {
          return api;
        }
        return each(function (element) {
          target.appendChild(element);
        });
      },
      addClass: function (value) {
        return each(function (element) {
          element.classList.add.apply(element.classList, value.split(/\s+/).filter(Boolean));
        });
      },
      removeClass: function (value) {
        return each(function (element) {
          element.classList.remove.apply(element.classList, value.split(/\s+/).filter(Boolean));
        });
      },
      toggleClass: function (value, force) {
        return each(function (element) {
          value.split(/\s+/).filter(Boolean).forEach(function (name) {
            if (typeof force === "boolean") {
              element.classList.toggle(name, force);
            } else {
              element.classList.toggle(name);
            }
          });
        });
      },
      text: function (value) {
        if (typeof value === "undefined") {
          return list[0] ? list[0].textContent : undefined;
        }
        return each(function (element) {
          element.textContent = value;
        });
      },
      val: function (value) {
        if (typeof value === "undefined") {
          return list[0] ? list[0].value : undefined;
        }
        return each(function (element) {
          element.value = value;
        });
      },
      prop: function (name, value) {
        if (typeof value === "undefined") {
          return list[0] ? list[0][name] : undefined;
        }
        return each(function (element) {
          element[name] = value;
        });
      },
      attr: function (name, value) {
        if (typeof value === "undefined") {
          return list[0] ? list[0].getAttribute(name) : undefined;
        }
        return each(function (element) {
          element.setAttribute(name, value);
        });
      },
      css: function (name, value) {
        if (typeof value === "undefined") {
          return list[0] ? list[0].style[name] : undefined;
        }
        return each(function (element) {
          element.style[name] = value;
        });
      },
      remove: function () {
        return each(function (element) {
          if (element.parentNode) {
            element.parentNode.removeChild(element);
          }
        });
      },
      empty: function () {
        return each(function (element) {
          while (element.firstChild) {
            element.removeChild(element.firstChild);
          }
        });
      },
      on: function (eventName, handler) {
        return each(function (element) {
          element.addEventListener(eventName, handler);
        });
      },
    };

    return api;
  }

  function createElementFromHtml(html, attributes) {
    var match = /^<\s*([a-z0-9-]+)/i.exec(html);
    var element = window.document.createElement(match ? match[1] : "div");
    var attrs = attributes || {};
    Object.keys(attrs).forEach(function (name) {
      setAttr(element, name, attrs[name]);
    });
    return wrap([element]);
  }

  function setAttr(element, name, value) {
    if (name === "class") {
      element.className = value;
      return;
    }
    if (name in element) {
      element[name] = value;
    } else {
      element.setAttribute(name, value);
    }
  }

  function $(selector, attributes) {
    if (typeof selector === "function") {
      if (window.document.readyState === "loading") {
        window.document.addEventListener("DOMContentLoaded", selector);
      } else {
        selector();
      }
      return;
    }

    if (typeof selector === "string" && selector.charAt(0) === "<") {
      return createElementFromHtml(selector, attributes);
    }

    return wrap(Array.prototype.slice.call(window.document.querySelectorAll(selector)));
  }

  $.ajax = function (options) {
    if (typeof window.__uiTestMockAjax === "function") {
      var abortedMock = false;
      var mockedResponse = window.__uiTestMockAjax(options) || {};
      window.setTimeout(function () {
        if (abortedMock) {
          return;
        }
        var status = typeof mockedResponse.status === "number" ? mockedResponse.status : 200;
        var responseText =
          typeof mockedResponse.responseText === "undefined" ? "" : mockedResponse.responseText;
        var fakeXhr = {
          status: status,
          statusText: mockedResponse.statusText || "",
          responseText: responseText,
        };
        if (status >= 200 && status < 300) {
          var response = responseText;
          if (mockedResponse.json || options.dataType === "json") {
            response = responseText ? JSON.parse(responseText) : null;
          }
          if (options.success) {
            options.success(response, "success", fakeXhr);
          }
          return;
        }
        if (options.error) {
          options.error(fakeXhr, "error", fakeXhr.statusText);
        }
      }, mockedResponse.delay || 0);
      return {
        abort: function () {
          abortedMock = true;
        },
      };
    }

    var xhr = new window.XMLHttpRequest();
    var aborted = false;
    var nativeAbort = xhr.abort ? xhr.abort.bind(xhr) : null;
    xhr.open(options.type || "GET", options.url, true);
    if (options.contentType) {
      xhr.setRequestHeader("Content-Type", options.contentType);
    }
    xhr.onreadystatechange = function () {
      if (xhr.readyState !== 4 || aborted) {
        return;
      }
      if (xhr.status >= 200 && xhr.status < 300) {
        var response = xhr.responseText;
        if (options.dataType === "json") {
          response = response ? JSON.parse(response) : null;
        }
        if (options.success) {
          options.success(response, "success", xhr);
        }
        return;
      }
      if (options.error) {
        options.error(xhr, "error", xhr.statusText);
      }
    };
    xhr.send(options.data || null);
    xhr.abort = function () {
      aborted = true;
      if (nativeAbort) {
        nativeAbort();
      }
    };
    return xhr;
  };

  $.getJSON = function (url, success) {
    return $.ajax({type: "GET", url: url, dataType: "json", success: success});
  };

  window.$ = window.jQuery = $;
})(window);
