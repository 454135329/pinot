var JS_BEAUTIFY_SETTINGS = {
  indent_size               : 4,
  indent_char               : ' ',
  preserve_newlines         : true,
  braces_on_own_line        : true,
  keep_array_indentation    : false,
  space_after_anon_function : true
};


$(document).ready(function() {
  EDITOR = CodeMirror.fromTextArea(document.getElementById('query-maker'), {
    lineWrapping: true,
    lineNumbers: true,
    mode: "sql",
    theme: "elegant"
  });
  
  RESULTS = CodeMirror.fromTextArea(document.getElementById('results-maker'), {
    mode         : 'ruby',
    value        : "",
    indentUnit   : 4,
    lineWrapping : true,
    lineNumbers  : false
  });

  var windowHeight = $(window).height();
  var documentHeight = $(document).height();

  var qH = (.20) * windowHeight;
  var rH = (.60) * windowHeight;

  $('.query-box').css('min-height', qH + 'px');
  $('.query-results').css('min-height', rH + 'px');

  HELPERS.printTables(function() {
    $('.table-name-entry').click(function(e) {
      var tableName = e.currentTarget.attributes[1].nodeValue;
      HELPERS.decorateTableSchema(tableName);
      EDITOR.setValue('');
      HELPERS.populateDefaultQuery(tableName);
      $("#execute-query").click();
    });
  });

  $("#execute-query").click(function() {
    // execute query and draw the results
    var query = EDITOR.getValue().trim();
    
    HELPERS.executeQuery(query, function(data) {
      RESULTS.setValue(js_beautify(data, JS_BEAUTIFY_SETTINGS));
    })
  });
});

var HELPERS = {
  printTables : function(callback) {
    $.get("/tables", function(data) {
      var source   = $("#table-names-template").html();
      var template = Handlebars.compile(source);
      var d = template({"tables" : JSON.parse(data)});
      $(".schema-list-view").html(d);
      $("#table-names").DataTable({
        "searching":true,
        "scrollY": "200px",
        "scrollCollapse": true,
        "paging": false,
        "info": false
      });
      callback();
    });
  },

  decorateTableSchema : function(tableName) {
    $.get("/tables/" + tableName + "/schema/", function(data){
      var schema = JSON.parse(data);
      var columnNames = Object.keys(schema.fieldSpecMap);
      var res = [];
      
      for (var i = 0; i < columnNames.length; i++) {
        res[i] = { name : columnNames[i], dataType : schema.fieldSpecMap[columnNames[i]].dataType, fieldType : schema.fieldSpecMap[columnNames[i]].fieldType}
      }
      var source   = $("#table-schema-template").html();
      var template = Handlebars.compile(source);
      var d = template({"columns" : res});
      $(".schema-detail-view").html(d);
    });
  },
  
  populateDefaultQuery: function(tableName) {
    EDITOR.setValue("select * from " + tableName + " limit 10");
  },

  populateQuery: function(query) {
    var query = EDITOR.getValue().trim();
  },

  executeQuery: function(query, callback) {
    var url = "/pql?pql=" + query;
    $.ajax({
      type: 'GET',
      url: url,
      contentType: 'application/json; charset=utf-8',
      success: function (text) {
        callback(text);
      },
      dataType: 'text'
    })
    .complete(function(){
    });
  }
};
