<?xml version="1.0" encoding="UTF-8" ?>
<xsl:stylesheet version="1.0"
	xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
	xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
	xmlns="http://www.w3.org/1999/xhtml"
	xmlns:xhtml="http://www.w3.org/1999/xhtml"
	exclude-result-prefixes="xhtml">
	<xsl:import href="../util/iriref.xsl" />
	<xsl:param name="this" />
	<xsl:template match="/">
		<html>
			<head>
				<title>
					<xsl:call-template name="iriref">
						<xsl:with-param name="iri" select="$this"/>
					</xsl:call-template>
				</title>
				<style>
					ul.properties { margin-top: 0px; }
					li.triple { list-style-type: none }
					.plain { font-size: large; }
					.bnode, .plain { font-family: monospace; white-space: pre-wrap; }
					.typed { color: magenta; }
					.datatype, .language { color: gray; }
					.predicate { color: darkgreen;  }
				</style>
			</head>
			<body>
				<h1>
					<xsl:call-template name="iriref">
						<xsl:with-param name="iri" select="$this"/>
					</xsl:call-template>
					<xsl:text> Graph</xsl:text>
				</h1>
				<xsl:apply-templates />
			</body>
		</html>
	</xsl:template>
	<xsl:template match="rdf:RDF">
		<div class="graph">
			<xsl:apply-templates select="*" />
		</div>
	</xsl:template>
	<xsl:template match="*">
		<li class="triple">
			<span class="predicate">
				<xsl:call-template name="iriref">
					<xsl:with-param name="iri" select="concat(namespace-uri(),local-name())" />
				</xsl:call-template>
			</span>
			<xsl:text> </xsl:text>
			<span class="plain literal">
				<xsl:attribute name="property">
					<xsl:call-template name="iriref">
						<xsl:with-param name="iri" select="concat(namespace-uri(),local-name())" />
					</xsl:call-template>
				</xsl:attribute>
				<xsl:apply-templates />
			</span>
		</li>
	</xsl:template>
	<xsl:template match="*[@rdf:nodeID]">
		<li class="triple">
			<span class="predicate">
				<xsl:call-template name="iriref">
					<xsl:with-param name="iri" select="concat(namespace-uri(),local-name())" />
				</xsl:call-template>
			</span>
			<xsl:text> </xsl:text>
			<a href="#{@rdf:nodeID}" class="bnode">
				<xsl:attribute name="rel">
					<xsl:call-template name="iriref">
						<xsl:with-param name="iri" select="concat(namespace-uri(),local-name())" />
					</xsl:call-template>
				</xsl:attribute>
				<xsl:text>_:</xsl:text>
				<xsl:value-of select="@rdf:nodeID" />
			</a>
		</li>
	</xsl:template>
	<xsl:template match="*[@rdf:resource]">
		<li class="triple">
			<span class="predicate">
				<xsl:call-template name="iriref">
					<xsl:with-param name="iri" select="concat(namespace-uri(),local-name())" />
				</xsl:call-template>
			</span>
			<xsl:text> </xsl:text>
			<a href="{@rdf:resource}" class="view uri">
				<xsl:attribute name="rel">
					<xsl:call-template name="iriref">
						<xsl:with-param name="iri" select="concat(namespace-uri(),local-name())" />
					</xsl:call-template>
				</xsl:attribute>
				<xsl:call-template name="iriref">
					<xsl:with-param name="iri" select="@rdf:resource"/>
				</xsl:call-template>
			</a>
		</li>
	</xsl:template>
	<xsl:template match="*[@rdf:datatype]">
		<li class="triple">
			<span class="predicate">
				<xsl:call-template name="iriref">
					<xsl:with-param name="iri" select="concat(namespace-uri(),local-name())" />
				</xsl:call-template>
			</span>
			<xsl:text> </xsl:text>
			<span class="typed literal">
				<xsl:attribute name="property">
					<xsl:call-template name="iriref">
						<xsl:with-param name="iri" select="concat(namespace-uri(),local-name())" />
					</xsl:call-template>
				</xsl:attribute>
				<xsl:attribute name="datatype">
					<xsl:call-template name="iriref">
						<xsl:with-param name="iri" select="@rdf:datatype" />
					</xsl:call-template>
				</xsl:attribute>
				<xsl:apply-templates />
				<span class="datatype">
					<span>^^</span>
					<xsl:call-template name="iriref">
						<xsl:with-param name="iri" select="@rdf:datatype" />
					</xsl:call-template>
				</span>
			</span>
		</li>
	</xsl:template>
	<xsl:template match="*[@xml:lang]">
		<li class="triple">
			<span class="predicate">
				<xsl:call-template name="iriref">
					<xsl:with-param name="iri" select="concat(namespace-uri(),local-name())" />
				</xsl:call-template>
			</span>
			<xsl:text> </xsl:text>
			<span class="plain literal">
				<xsl:attribute name="property">
					<xsl:call-template name="iriref">
						<xsl:with-param name="iri" select="concat(namespace-uri(),local-name())" />
					</xsl:call-template>
				</xsl:attribute>
				<xsl:attribute name="xml:lang">
					<xsl:value-of select="@xml:lang" />
				</xsl:attribute>
				<xsl:apply-templates />
				<span class="language">
					<span>@</span>
					<xsl:value-of select="@xml:lang" />
				</span>
			</span>
		</li>
	</xsl:template>
	<xsl:template match="*[@rdf:parseType='Literal']">
		<li class="triple">
			<span class="predicate">
				<xsl:call-template name="iriref">
					<xsl:with-param name="iri" select="concat(namespace-uri(),local-name())" />
				</xsl:call-template>
			</span>
			<xsl:text> </xsl:text>
			<span class="typed literal">
				<xsl:attribute name="property">
					<xsl:call-template name="iriref">
						<xsl:with-param name="iri" select="concat(namespace-uri(),local-name())" />
					</xsl:call-template>
				</xsl:attribute>
				<xsl:copy-of select="node()" />
			</span>
		</li>
	</xsl:template>
	<xsl:template match="*[@rdf:parseType='Resource']">
		<li class="triple">
			<span class="predicate">
				<xsl:call-template name="iriref">
					<xsl:with-param name="iri" select="concat(namespace-uri(),local-name())" />
				</xsl:call-template>
			</span>
			<ul class="properties">
				<xsl:attribute name="rel">
					<xsl:call-template name="iriref">
						<xsl:with-param name="iri" select="concat(namespace-uri(),local-name())" />
					</xsl:call-template>
				</xsl:attribute>
				<xsl:apply-templates select="*" />
			</ul>
		</li>
	</xsl:template>
	<xsl:template match="*[@rdf:parseType='Collection']">
		<li class="triple">
			<span class="predicate">
				<xsl:call-template name="iriref">
					<xsl:with-param name="iri" select="concat(namespace-uri(),local-name())" />
				</xsl:call-template>
			</span>
			<ol class="collection">
				<xsl:apply-templates select="*" />
			</ol>
		</li>
	</xsl:template>
	<xsl:template match="*[@rdf:about]">
		<div about="{@rdf:about}">
			<xsl:attribute name="typeof">
				<xsl:call-template name="iriref">
					<xsl:with-param name="iri" select="concat(namespace-uri(),local-name())" />
				</xsl:call-template>
			</xsl:attribute>
			<a href="{@rdf:about}" class="view uri">
				<xsl:call-template name="iriref">
					<xsl:with-param name="iri" select="@rdf:about"/>
				</xsl:call-template>
			</a>
			<span> a </span>
			<span>
				<xsl:call-template name="iriref">
					<xsl:with-param name="iri" select="concat(namespace-uri(),local-name())" />
				</xsl:call-template>
			</span>
			<ul class="properties">
				<xsl:apply-templates select="*" />
			</ul>
		</div>
	</xsl:template>
	<xsl:template match="*[@rdf:ID]">
		<div about="#{@rdf:ID}">
			<xsl:attribute name="typeof">
				<xsl:call-template name="iriref">
					<xsl:with-param name="iri" select="concat(namespace-uri(),local-name())" />
				</xsl:call-template>
			</xsl:attribute>
			<a href="#{@rdf:ID}" class="view uri">
				<xsl:value-of select="@rdf:ID"/>
			</a>
			<span> a </span>
			<span>
				<xsl:call-template name="iriref">
					<xsl:with-param name="iri" select="concat(namespace-uri(),local-name())" />
				</xsl:call-template>
			</span>
			<ul class="properties">
				<xsl:apply-templates select="*" />
			</ul>
		</div>
	</xsl:template>
	<xsl:template match="*[@rdf:nodeID][*]">
		<div about="_:{@rdf:nodeID}">
			<xsl:attribute name="typeof">
				<xsl:call-template name="iriref">
					<xsl:with-param name="iri" select="concat(namespace-uri(),local-name())" />
				</xsl:call-template>
			</xsl:attribute>
			<a name="{@rdf:nodeID}" id="{@rdf:nodeID}" class="bnode">
				<xsl:text>_:</xsl:text>
				<xsl:value-of select="@rdf:nodeID" />
			</a>
			<span> a </span>
			<span>
				<xsl:call-template name="iriref">
					<xsl:with-param name="iri" select="concat(namespace-uri(),local-name())" />
				</xsl:call-template>
			</span>
			<ul class="properties">
				<xsl:apply-templates select="*" />
			</ul>
		</div>
	</xsl:template>
	<xsl:template match="rdf:Description[@rdf:about]">
		<div about="{@rdf:about}">
			<a href="{@rdf:about}" class="view uri">
				<xsl:if test="substring-before(@rdf:about, '#')=$this">
					<xsl:attribute name="name"><xsl:value-of select="substring-after(@rdf:about, '#')" /></xsl:attribute>
				</xsl:if>
				<xsl:call-template name="iriref">
					<xsl:with-param name="iri" select="@rdf:about"/>
				</xsl:call-template>
			</a>
			<ul class="properties">
				<xsl:apply-templates select="*" />
			</ul>
		</div>
	</xsl:template>
	<xsl:template match="rdf:Description[@rdf:ID]">
		<div about="#{@rdf:ID}">
			<a href="#{@rdf:ID}" class="view uri" name="{@rdf:ID}">
				<xsl:value-of select="@rdf:ID"/>
			</a>
			<ul class="properties">
				<xsl:apply-templates select="*" />
			</ul>
		</div>
	</xsl:template>
	<xsl:template match="rdf:Description[@rdf:nodeID]">
		<div about="_:{@rdf:nodeID}">
			<a name="{@rdf:nodeID}" id="{@rdf:nodeID}" class="bnode">
				<xsl:text>_:</xsl:text>
				<xsl:value-of select="@rdf:nodeID" />
			</a>
			<ul class="properties">
				<xsl:apply-templates select="*" />
			</ul>
		</div>
	</xsl:template>
</xsl:stylesheet>
