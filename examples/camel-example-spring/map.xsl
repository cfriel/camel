<?xml version="1.0"?>
<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
		version="1.0">
  <xsl:template match="/">
    <routes xmlns="http://camel.apache.org/schema/spring">
      <xsl:for-each select="map/entry/list/linked-hash-map/entry/linked-hash-map">
	<route>
	  <xsl:variable name="LocalName" select="entry/string[1]"/>	  
	  <xsl:attribute name="{$LocalName}">
	    <xsl:value-of select="entry/string[2]"/>
	  </xsl:attribute>
	  <xsl:for-each select="entry/list/linked-hash-map/entry">
	    <xsl:variable name="LocalItem" select="string"/>
	    <xsl:element name="{$LocalItem}">
	      <xsl:for-each select="linked-hash-map/entry">
		<xsl:variable name="LocalAttr" select="string[1]"/>	  
		<xsl:attribute name="{$LocalAttr}">
		  <xsl:value-of select="string[2]"/>
		</xsl:attribute>
	      </xsl:for-each>
	    </xsl:element>
	  </xsl:for-each>
	</route>
      </xsl:for-each>
      </routes>
  </xsl:template>
</xsl:stylesheet>