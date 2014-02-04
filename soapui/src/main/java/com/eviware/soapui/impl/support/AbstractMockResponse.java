package com.eviware.soapui.impl.support;

import com.eviware.soapui.SoapUI;
import com.eviware.soapui.config.BaseMockResponseConfig;
import com.eviware.soapui.config.CompressedStringConfig;
import com.eviware.soapui.config.HeaderConfig;
import com.eviware.soapui.impl.wsdl.AbstractWsdlModelItem;
import com.eviware.soapui.impl.wsdl.MutableWsdlAttachmentContainer;
import com.eviware.soapui.impl.wsdl.WsdlOperation;
import com.eviware.soapui.impl.wsdl.mock.DispatchException;
import com.eviware.soapui.impl.wsdl.mock.WsdlMockOperation;
import com.eviware.soapui.impl.wsdl.mock.WsdlMockRequest;
import com.eviware.soapui.impl.wsdl.mock.WsdlMockRunContext;
import com.eviware.soapui.impl.wsdl.submit.transports.http.support.attachments.AttachmentUtils;
import com.eviware.soapui.impl.wsdl.submit.transports.http.support.attachments.MimeMessageMockResponseEntity;
import com.eviware.soapui.impl.wsdl.submit.transports.http.support.attachments.MockResponseDataSource;
import com.eviware.soapui.impl.wsdl.support.*;
import com.eviware.soapui.model.ModelItem;
import com.eviware.soapui.model.TestPropertyHolder;
import com.eviware.soapui.model.iface.Operation;
import com.eviware.soapui.model.mock.*;
import com.eviware.soapui.model.propertyexpansion.PropertyExpander;
import com.eviware.soapui.model.propertyexpansion.PropertyExpansionContainer;
import com.eviware.soapui.model.testsuite.TestProperty;
import com.eviware.soapui.model.testsuite.TestPropertyListener;
import com.eviware.soapui.settings.CommonSettings;
import com.eviware.soapui.support.scripting.ScriptEnginePool;
import com.eviware.soapui.support.scripting.SoapUIScriptEngine;
import com.eviware.soapui.support.types.StringToStringMap;
import com.eviware.soapui.support.types.StringToStringsMap;
import com.eviware.soapui.support.xml.XmlUtils;
import org.apache.ws.security.WSSecurityException;
import org.apache.xmlbeans.XmlException;

import javax.activation.DataHandler;
import javax.mail.MessagingException;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import javax.mail.internet.PreencodedMimeBodyPart;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public abstract class AbstractMockResponse<MockResponseConfigType extends BaseMockResponseConfig>
		extends AbstractWsdlModelItem<MockResponseConfigType>
		implements MockResponse, MutableWsdlAttachmentContainer, PropertyExpansionContainer, TestPropertyHolder
{
	public static final String AUTO_RESPONSE_COMPRESSION = "<auto>";
	public static final String NO_RESPONSE_COMPRESSION = "<none>";
	private MapTestPropertyHolder propertyHolder;

	private String responseContent;
	private MockResult mockResult;
	private ScriptEnginePool scriptEnginePool;


	public AbstractMockResponse( MockResponseConfigType config, MockOperation operation, String icon )
	{
		super( config, operation, icon )
		;
		scriptEnginePool = new ScriptEnginePool( this );
		scriptEnginePool.setScript( getScript() );
		propertyHolder = new MapTestPropertyHolder( this );
		propertyHolder.addProperty( "Request" );

	}

	@Override
	public void setConfig( MockResponseConfigType config )
	{
		super.setConfig( config );

		if( scriptEnginePool != null )
			scriptEnginePool.setScript( getScript() );
	}

	public String getResponseContent()
	{
		if( getConfig().getResponseContent() == null )
			getConfig().addNewResponseContent();

		if( responseContent == null )
			responseContent = CompressedStringSupport.getString( getConfig().getResponseContent() );

		return responseContent;
	}

	public void setResponseContent( String responseContent )
	{
		String oldContent = getResponseContent();
		if( responseContent != null && responseContent.equals( oldContent ) )
			return;

		this.responseContent = responseContent;

		setConfigResponseContent( responseContent );

		notifyPropertyChanged( RESPONSE_CONTENT_PROPERTY, oldContent, responseContent );
	}

	private void setConfigResponseContent( String responseContent )
	{
		CompressedStringConfig compressedResponseContent = CompressedStringConfig.Factory.newInstance();
		compressedResponseContent.setStringValue( responseContent );
		getConfig().setResponseContent( compressedResponseContent );
	}

	public StringToStringsMap getResponseHeaders()
	{
		StringToStringsMap result = new StringToStringsMap();
		List<HeaderConfig> headerList = getConfig().getHeaderList();
		for( HeaderConfig header : headerList )
		{
			result.add( header.getName(), header.getValue() );
		}

		return result;
	}

	public void setResponseHttpStatus( String httpStatus )
	{
		String oldStatus = getResponseHttpStatus();

		getConfig().setHttpResponseStatus( httpStatus );
	}

	public String getResponseHttpStatus()
	{
		return getConfig().getHttpResponseStatus();
	}

	public String getResponseCompression()
	{
		if( getConfig().isSetCompression() )
			return getConfig().getCompression();
		else
			return AUTO_RESPONSE_COMPRESSION;
	}

	public void setMockResult( MockResult mockResult )
	{
		MockResult oldResult = this.mockResult;
		this.mockResult = mockResult;
		notifyPropertyChanged( mockresultProperty(), oldResult, mockResult );
	}

	public MockResult getMockResult()
	{
		return mockResult;
	}

	protected abstract String mockresultProperty();

	public String getScript()
	{
		return getConfig().isSetScript() ? getConfig().getScript().getStringValue() : null;
	}

	public void evaluateScript( MockRequest request ) throws Exception
	{
		String script = getScript();
		if( script == null || script.trim().length() == 0 )
			return;

		MockService mockService = getMockOperation().getMockService();
		MockRunner mockRunner = mockService.getMockRunner();
		MockRunContext context =
				mockRunner == null ? new WsdlMockRunContext( mockService, null ) : mockRunner.getMockContext();

		context.setMockResponse( this );

		SoapUIScriptEngine scriptEngine = scriptEnginePool.getScriptEngine();

		try
		{
			scriptEngine.setVariable( "context", context );
			scriptEngine.setVariable( "requestContext", request == null ? null : request.getRequestContext() );
			scriptEngine.setVariable( "mockContext", context );
			scriptEngine.setVariable( "mockRequest", request );
			scriptEngine.setVariable( "mockResponse", this );
			scriptEngine.setVariable( "log", SoapUI.ensureGroovyLog() );

			scriptEngine.run();
		}
		catch( RuntimeException e )
		{
			throw new Exception( e.getMessage(), e );
		}
		finally
		{
			scriptEnginePool.returnScriptEngine( scriptEngine );
		}
	}

	public void setScript( String script )
	{
		String oldScript = getScript();
		if( !script.equals( oldScript ) )
		{
			if( !getConfig().isSetScript() )
				getConfig().addNewScript();
			getConfig().getScript().setStringValue( script );

			scriptEnginePool.setScript( script );
		}
	}

	@Override
	public void release()
	{
		super.release();
		scriptEnginePool.release();
	}

	public MockResult execute( MockRequest request, MockResult result ) throws DispatchException
	{
		try
		{
			getProperty( "Request" ).setValue( request.getRequestContent() );

			long delay = getResponseDelay();
			if( delay > 0 )
				Thread.sleep( delay );

			String script = getScript();
			if( script != null && script.trim().length() > 0 )
			{
				evaluateScript( request );
			}

			String responseContent = getResponseContent();

			// create merged context
			WsdlMockRunContext context = new WsdlMockRunContext( request.getContext().getMockService(), null );
			context.setMockResponse( this );

			// casting below cause WsdlMockRunContext is both a MockRunContext AND a Map<String,Object>
			context.putAll( (WsdlMockRunContext)request.getContext() );
			context.putAll( (WsdlMockRunContext)request.getRequestContext() );

			StringToStringsMap responseHeaders = getResponseHeaders();
			for( Map.Entry<String, List<String>> headerEntry : responseHeaders.entrySet() )
			{
				for( String value : headerEntry.getValue() )
					result.addHeader( headerEntry.getKey(), PropertyExpander.expandProperties( context, value ) );
			}

			responseContent = PropertyExpander.expandProperties( context, responseContent, isEntitizeProperties() );

			responseContent = executeSpecifics( request, responseContent, context );

			if( !result.isCommitted() )
			{
				responseContent = writeResponse( result, responseContent );
			}

			result.setResponseContent( responseContent );

			setMockResult( result );

			return result;
		}
		catch( Throwable e )
		{
			SoapUI.logError( e );
			throw new DispatchException( e );
		}
	}

	protected String writeResponse( MockResult response, String responseContent ) throws Exception
	{
		MimeMultipart mp = null;

		Operation operation = getMockOperation().getOperation();

		// variables needed for both multipart sections....
		boolean isXOP = isMtomEnabled() && isForceMtom();
		StringToStringMap contentIds = new StringToStringMap();

		// only support multipart for wsdl currently.....
		if(operation instanceof WsdlMockOperation)
		{
			if( operation == null )
				throw new Exception( "Missing WsdlOperation for mock response" );


			// preprocess only if neccessary
			if( isMtomEnabled() || isInlineFilesEnabled() || getAttachmentCount() > 0 )
			{
				try
				{
					mp = new MimeMultipart();

					MessageXmlObject requestXmlObject = new MessageXmlObject( (WsdlOperation)operation, responseContent, false );
					MessageXmlPart[] requestParts = requestXmlObject.getMessageParts();
					for( MessageXmlPart requestPart : requestParts )
					{
						if( AttachmentUtils.prepareMessagePart( this, mp, requestPart, contentIds ) )
							isXOP = true;
					}
					responseContent = requestXmlObject.getMessageContent();
				}
				catch( Exception e )
				{
					e.printStackTrace();
				}
			}
		}

		responseContent = removeEmptyContent( responseContent );

		if( isStripWhitespaces() )
		{
			responseContent = XmlUtils.stripWhitespaces( responseContent );
		}

		String status = getResponseHttpStatus();
		MockRequest request = response.getMockRequest();

		if( status == null || status.trim().length() == 0 )
		{
			if( isFault( responseContent, request ) )
			{
				request.getHttpResponse().setStatus( HttpServletResponse.SC_INTERNAL_SERVER_ERROR );
				response.setResponseStatus( HttpServletResponse.SC_INTERNAL_SERVER_ERROR );
			}
			else
			{
				request.getHttpResponse().setStatus( HttpServletResponse.SC_OK );
				response.setResponseStatus( HttpServletResponse.SC_OK );
			}
		}
		else
		{
			try
			{
				int statusCode = Integer.parseInt( status );
				request.getHttpResponse().setStatus( statusCode );
				response.setResponseStatus( statusCode );
			}
			catch( RuntimeException e )
			{
				SoapUI.logError( e );
			}
		}

		ByteArrayOutputStream outData = new ByteArrayOutputStream();

		// non-multipart request?
		String responseCompression = getResponseCompression();
		if( !isXOP && ( mp == null || mp.getCount() == 0 ) && getAttachmentCount() == 0 )
		{
			String encoding = getEncoding();
			if( responseContent == null )
				responseContent = "";

			byte[] content = encoding == null ? responseContent.getBytes() : responseContent.getBytes( encoding );

			if( !response.getResponseHeaders().containsKeyIgnoreCase( "Content-Type" ) )
			{
				response.setContentType( getContentType( operation, encoding ) );
			}

			String acceptEncoding = response.getMockRequest().getRequestHeaders().get( "Accept-Encoding", "" );
			if( AUTO_RESPONSE_COMPRESSION.equals( responseCompression ) && acceptEncoding != null
					&& acceptEncoding.toUpperCase().contains( "GZIP" ) )
			{
				response.addHeader( "Content-Encoding", "gzip" );
				outData.write( CompressionSupport.compress( CompressionSupport.ALG_GZIP, content ) );
			}
			else if( AUTO_RESPONSE_COMPRESSION.equals( responseCompression ) && acceptEncoding != null
					&& acceptEncoding.toUpperCase().contains( "DEFLATE" ) )
			{
				response.addHeader( "Content-Encoding", "deflate" );
				outData.write( CompressionSupport.compress( CompressionSupport.ALG_DEFLATE, content ) );
			}
			else
			{
				outData.write( content );
			}
		}
		else // won't get here if rest at the moment...
		{
			// make sure..
			if( mp == null )
				mp = new MimeMultipart();

			// init root part
			initRootPart( responseContent, mp, isXOP );

			// init mimeparts
			AttachmentUtils.addMimeParts( this, Arrays.asList( getAttachments() ), mp, contentIds );

			// create request message
			MimeMessage message = new MimeMessage( AttachmentUtils.JAVAMAIL_SESSION );
			message.setContent( mp );
			message.saveChanges();
			MimeMessageMockResponseEntity mimeMessageRequestEntity
					= new MimeMessageMockResponseEntity( message, isXOP, this );

			response.addHeader( "Content-Type", mimeMessageRequestEntity.getContentType().getValue() );
			response.addHeader( "MIME-Version", "1.0" );
			mimeMessageRequestEntity.writeTo( outData );
		}

		if( outData.size() > 0 )
		{
			byte[] data = outData.toByteArray();

			if( responseCompression.equals( CompressionSupport.ALG_DEFLATE )
					|| responseCompression.equals( CompressionSupport.ALG_GZIP ) )
			{
				response.addHeader( "Content-Encoding", responseCompression );
				data = CompressionSupport.compress( responseCompression, data );
			}

			response.writeRawResponseData( data );
		}

		return responseContent;
	}

	protected abstract String getContentType( Operation operation, String encoding );

	private void initRootPart( String requestContent, MimeMultipart mp, boolean isXOP ) throws MessagingException
	{
		MimeBodyPart rootPart = new PreencodedMimeBodyPart( "8bit" );
		rootPart.setContentID( AttachmentUtils.ROOTPART_SOAPUI_ORG );
		mp.addBodyPart( rootPart, 0 );

		DataHandler dataHandler = new DataHandler( new MockResponseDataSource( this, requestContent, isXOP ) );
		rootPart.setDataHandler( dataHandler );
	}

	protected abstract boolean isFault( String responseContent, MockRequest request ) throws XmlException;

	protected abstract String removeEmptyContent( String responseContent );

	protected abstract String executeSpecifics( MockRequest request, String responseContent, WsdlMockRunContext context ) throws IOException, WSSecurityException;

	public boolean isEntitizeProperties()
	{
		return getSettings().getBoolean( CommonSettings.ENTITIZE_PROPERTIES );
	}

	public abstract long getResponseDelay();

	public abstract boolean isForceMtom();

	public abstract boolean isStripWhitespaces();

	public void addTestPropertyListener( TestPropertyListener listener )
	{
		propertyHolder.addTestPropertyListener( listener );
	}

	public ModelItem getModelItem()
	{
		return propertyHolder.getModelItem();
	}

	public Map<String, TestProperty> getProperties()
	{
		return propertyHolder.getProperties();
	}

	public TestProperty getProperty( String name )
	{
		return propertyHolder.getProperty( name );
	}

	public String[] getPropertyNames()
	{
		return propertyHolder.getPropertyNames();
	}

	public String getPropertyValue( String name )
	{
		return propertyHolder.getPropertyValue( name );
	}

	public boolean hasProperty( String name )
	{
		return propertyHolder.hasProperty( name );
	}

	public void removeTestPropertyListener( TestPropertyListener listener )
	{
		propertyHolder.removeTestPropertyListener( listener );
	}

	public void setPropertyValue( String name, String value )
	{
		propertyHolder.setPropertyValue( name, value );
	}


	public TestProperty getPropertyAt( int index )
	{
		return propertyHolder.getPropertyAt( index );
	}

	public int getPropertyCount()
	{
		return propertyHolder.getPropertyCount();
	}

	public List<TestProperty> getPropertyList()
	{
		return propertyHolder.getPropertyList();
	}

}
