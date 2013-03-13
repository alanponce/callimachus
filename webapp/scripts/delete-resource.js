// delete-resource.js
/*
   Copyright (c) 2011 3 Round Stones Inc., Some Rights Reserved
   Licensed under the Apache License, Version 2.0, http://www.apache.org/licenses/LICENSE-2.0
*/

(function($, jQuery){

if (!window.calli) {
    window.calli = {};
}
window.calli.deleteResource = function(event, redirect) {
    var form = $(calli.fixEvent(event).target).closest('form');

    if (event && event.type) {
        var prompt = event.message;
        if (typeof prompt === "undefined") {
            prompt = "Are you sure you want to delete " + document.title + "?";
        }
        if (prompt && !confirm(prompt))
            return;
    }

    var url = calli.getPageUrl();
    if (form.length) {
        url = calli.getFormAction(form[0]);
    } else {
        form = $(document);
    }
    if (url.indexOf('?') > 0) {
        url = url.substring(0, url.indexOf('?'));
    }
    try {
        var de = jQuery.Event("calliDelete");
        de.location = url;
        form.trigger(de);
        if (!de.isDefaultPrevented()) {
            var xhr = $.ajax({ type: "DELETE", url: de.location, dataType: "text", beforeSend: function(xhr){
                var lastmod = getLastModified();
                if (lastmod) {
                    xhr.setRequestHeader("If-Unmodified-Since", lastmod);
                }
                calli.withCredentials(xhr);
            }, complete: function(xhr) {
                try {
                    if (xhr.status >= 200) {
                        var event = jQuery.Event("calliRedirect");
                        event.cause = de;
                        event.location = redirect;
                        if (!event.location && xhr.getResponseHeader('Content-Type') == 'text/uri-list') {
                            event.location = xhr.responseText;
                        }
                        if (!event.location && location.pathname.match(/\/$/)) {
                            event.location = '../';
                        } else if (!event.location) {
                            event.location = './';
                        }
                        form.trigger(event)
                        if (!event.isDefaultPrevented()) {
                            if (event.location) {
                                window.location.replace(event.location);
                            } else {
                                window.location.replace('/');
                            }
                        }
                    }
                } catch(e) {
                    throw calli.error(e);
                }
            }});
        }
    } catch(e) {
        calli.error(e);
    }
}

function getLastModified() {
    try {
        var committedOn = $('#resource-lastmod').find('[property=audit:committedOn]').attr('content');
        return new Date(committedOn).toGMTString();
    } catch (e) {
        return null;
    }
}

})(jQuery, jQuery);

