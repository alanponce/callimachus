<?xml version="1.0" encoding="UTF-8" ?>
<p:pipeline version="1.0"
        xmlns:p="http://www.w3.org/ns/xproc"
        xmlns:c="http://www.w3.org/ns/xproc-step"
        xmlns:l="http://xproc.org/library">

    <p:serialization port="result" media-type="text/javascript" method="text" encoding="UTF-8" />

    <p:option name="handler" required="true" />
    <p:option name="reqId" required="true" />

    <p:xslt>
        <p:with-param name="handler" select="$handler" />
        <p:with-param name="reqId" select="$reqId" />
        <p:input port="stylesheet">
            <p:document href="../transforms/sparql-results-wire.xsl" />
        </p:input>
    </p:xslt>

</p:pipeline>