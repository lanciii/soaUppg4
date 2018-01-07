/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package RegResResource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.jms.ConnectionFactory;
import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.Queue;
import javax.jms.Session;
import javax.jms.TextMessage;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.UriInfo;
import javax.ws.rs.Consumes;
import javax.ws.rs.Produces;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PUT;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import org.apache.activemq.ActiveMQConnection;
import org.apache.activemq.ActiveMQConnectionFactory;

/**
 * REST Web Service
 */
@Path("RegRes")
public class RegRes{

    String appCode = "";
    String studOnCourse = "";
    boolean regKlar = false;
    
    private static String url = ActiveMQConnection.DEFAULT_BROKER_URL;
    // broker URL: tcp://localhost:61616"
    
    private static String subject = "LTU_Grade_Registered_Mail_Queue";
        
   
 
    @Context
    private UriInfo context;

    
    /**
     * Creates a new instance of RegResWS
     */
    public RegRes() {
    }
    
    /**
     * Retrieves representation of an instance of RegResResource.RegRes
     * @return an instance of java.lang.String
     */

    
    // Tar emot anrop från GUI
    @POST
    @Path("/registrera")
    @Produces("text/plain")
    public String registrera(@QueryParam("studentID") String studentID, @QueryParam("kurskod") String kurskod, @QueryParam("termin") String termin, @QueryParam("provnr") String provnr, @QueryParam("betyg") String betyg) {
        
        // Anropa GetAppCodeWS. Avbryt om kurskod inte finns.
        callGetAppCode(kurskod,termin);
        if(appCode.equals("Invalid")){
            return "Kursen eller terminen finns inte.";
        }
        
        // Anropa CheckStudOnCourseWS. Avbryt om student inte är registrerad på kursen.
        callCheckStudOnCourse(studentID,kurskod+termin);
        if(studOnCourse.equals("Invalid")){
            return "Student är inte registrerad på kurs.";
        }
        
        // Lagra resultatet i databas. 
        regKlar = registerGrade(studentID,kurskod+termin,provnr,betyg);
        if(regKlar == true){
            return "Registrering klar för " + studentID;
        }
        return "Registreringen misslyckades.";
        
    }
    
    // Anropar GetAppCodeWS med queryparameters. Får tillbaka appCode eller Invalid.
    public void callGetAppCode(@QueryParam("kurskod") String kurskod, @QueryParam("termin") String termin){
        
        try {
            kurskod = String.format("kurskod=%s", URLEncoder.encode(kurskod, "UTF-8"));
            termin = String.format("termin=%s", URLEncoder.encode(termin, "UTF-8"));
            String urlEndpoint = "http://localhost:8080/GetAppCodeWS/webresources/GetAppCode?";     // adress RegResWS
            
            URL url = new URL(urlEndpoint+kurskod+"&"+termin);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "text/plain");
            

            BufferedReader br = new BufferedReader(new InputStreamReader(
                    (conn.getInputStream())));
            
            // Lagrar appCode-variabeln för senare bruk
            this.appCode = br.readLine();
            
        } catch (MalformedURLException ex) {
            Logger.getLogger(RegRes.class.getName()).log(Level.SEVERE, null, ex);
        } catch (IOException ex) {
            Logger.getLogger(RegRes.class.getName()).log(Level.SEVERE, null, ex);
        } 
  
    }
    
    
    // Anropar CheckStudOnCourseWS. Får tillbaka Valid/Invalid som sparas för logik i registrera-metoden
    public void callCheckStudOnCourse(@QueryParam("studentID") String studentID, @QueryParam("appCode") String appCode){
        try {
            studentID = String.format("studentID=%s", URLEncoder.encode(studentID, "UTF-8"));
            appCode = String.format("appCode=%s", URLEncoder.encode(appCode, "UTF-8"));
            String urlEndpoint = "http://localhost:8080/CheckStudOnCourseWS/webresources/CheckStud?";     // adress RegResWS
            
            URL url = new URL(urlEndpoint+studentID+"&"+appCode);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "text/plain");


            BufferedReader br = new BufferedReader(new InputStreamReader(
                    (conn.getInputStream())));
            this.studOnCourse = br.readLine();    
        } catch (MalformedURLException ex) {
            Logger.getLogger(RegRes.class.getName()).log(Level.SEVERE, null, ex);
        } catch (IOException ex) {
            Logger.getLogger(RegRes.class.getName()).log(Level.SEVERE, null, ex);
        } 
    }
    
    
    // Registrerar resultatet i databasen.
    public boolean registerGrade(String studentID, String appCode, String provnr, String betyg){
        
        try {
            final String DBURL = "jdbc:sqlserver://127.0.0.1\\MSSQLEXPRESS:1434;databaseName=soaUppg3";
            final String USER = "admin";
            final String PWD = "12345";
            Connection conn = null; //Hanterar uppkoppling
            if (conn == null){    
                Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
                conn = DriverManager.getConnection(DBURL, USER, PWD);
            }
            PreparedStatement stmt = conn.prepareStatement("INSERT INTO Resultat(StudentID,AppCode,ProvNr,Betyg) VALUES(?,?,?,?)");
            stmt.setString(1, studentID);
            stmt.setString(2, appCode);
            stmt.setString(3, provnr);
            stmt.setString(4, betyg);
            stmt.executeUpdate();

            if (conn != null) {
                conn.close();
            }
            
            //Skicka till mailkö för notifiering till student att betyg är registrerat
            try {
                queueStudGradeMail(studentID);
            } catch (JMSException ex) {
                Logger.getLogger(RegRes.class.getName()).log(Level.SEVERE, null, ex);
            }
            
            try {
                consumeStudGradeMail();
            } catch (JMSException ex) {
                Logger.getLogger(RegRes.class.getName()).log(Level.SEVERE, null, ex);
            }

        } catch (ClassNotFoundException | SQLException ex) {
            Logger.getLogger(RegRes.class.getName()).log(Level.SEVERE, null, ex);
        }
        
        return true;
    }
    
    //Skickar studentID till en kö för mailnotifiering av registrerat betyg
    public void queueStudGradeMail(String studID) throws JMSException{
        // Getting JMS connection from the server and starting it
        ConnectionFactory connectionFactory = new ActiveMQConnectionFactory(url);
        javax.jms.Connection connection = connectionFactory.createConnection();
        connection.start();

        Session session = connection.createSession(false,
        Session.AUTO_ACKNOWLEDGE);

        Destination destination = session.createQueue(subject);

        MessageProducer producer = session.createProducer(destination);

        TextMessage message = session.createTextMessage(studID + "@student.ltu.se");
        producer.send(message);

        connection.close();
    }
    
    public void consumeStudGradeMail() throws JMSException{
        
        // Funktionalitet kan säkerställas genom att se att meddelanden blir konsumerade
        // på localhost:8161 ActiveMQ-adminportalen
        
        ConnectionFactory connectionFactory = new ActiveMQConnectionFactory(url);
        javax.jms.Connection connection = connectionFactory.createConnection();
        connection.start();
        Session session = connection.createSession(false,Session.AUTO_ACKNOWLEDGE);
        //Destination destination = session.createQueue(subject);
        Queue destination = session.createQueue(subject);
        MessageConsumer consumer = session.createConsumer(destination);    

        //Loopar igenom alla meddelanden i kön, om det skulle ligga tidigare
        while(true){
            
            //Letar meddelanden i 500 ms
            Message msg = consumer.receive(500);
            if (msg instanceof TextMessage) {
                TextMessage tm = (TextMessage) msg;
                System.out.println(tm.getText()); 
                // Här ska kod finnas som notifierar student att betyg är registrerat, 
                // baserat på att studentens mail är inskickat till kön som ett meddelande.
                // Kodparti inte byggt eftersom mailadresserna ändå inte skulle stämma.              
            }
            else{
                connection.stop();
                break;
            }
        }
        connection.close();
    }
    
    
    

    
    /**
     * PUT method for updating or creating an instance of RegRes
     * @param content representation for the resource
     */
    @PUT
    @Consumes(MediaType.TEXT_PLAIN)
    public void putText(String content) {
    }
}
