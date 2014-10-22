// file-create.js
/*
   Copyright (c) 2014 3 Round Stones Inc., Some Rights Reserved
   Licensed under the Apache License, Version 2.0, http://www.apache.org/licenses/LICENSE-2.0
*/

jQuery(function($){
    $('#form').submit(function(event){
        event.preventDefault();
        var action = calli.getFormAction(event.target);
        var input = $(event.target).find('input[type="file"]');
        var fileName = input.val().replace(/.*\/|.*\\/,'');
        var local = encodeURI(fileName).replace(/%25(\\w\\w)/g, '%$1').replace(/%20/g, '-');
        var ns = action.replace(/\/?\?.*|\/?#.*/,'/');
        var resource = ns + local;
        var xhr = $.ajax({
            type: 'HEAD',
            url: resource,
            dataType: "text"
        });
        calli.resolve(xhr).then(function(){
            if (confirm(local + " already exists. Do you want to replace it?")) {
                return calli.all(input.toArray().reduce(function(files, input) {
                    return Array.prototype.concat.apply(files, input.files);
                }, []).map(function(file){
                    var contentType = file.type && file.type.indexOf('/x-') < 0 && file.type ||
                        xhr.getResponseHeader('Content-Type') || "application/octet-stream";
                    return calli.putText(resource, file, contentType).then(function(){
                        if (window.parent != window && parent.postMessage) {
                            parent.postMessage('POST resource\n\n' + resource, '*');
                        }
                        window.location.replace(resource + '?view');
                    });
                }));
            }
        }, function(xhr){
            if (xhr.status == 404)
                return calli.submitForm(event);
            return calli.reject(xhr);
        }).then(undefined, calli.error);
    });
});