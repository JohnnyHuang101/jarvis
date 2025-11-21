import { useState, useRef, useEffect } from 'react';
import './App.css';
import stickerImage1 from './assets/images/vro.jpg'; // Make sure paths are correct  const NUMBER_OF_STICKERS = 15;

// 1. Define the Message Interface/Type
// This enforces that every message object must have a 'role' and 'content' property.
interface ChatMessage {
  role: 'user' | 'bot'; // Role can only be 'user' or 'bot' (Discriminated Union)
  content: string;
}

function App() {
  // 2. Explicitly Type the State: useState<ChatMessage[]>
  // This tells TypeScript that 'messages' is an array where every item is a ChatMessage.
  const [messages, setMessages] = useState<ChatMessage[]>([
    { role: 'bot', content: "Hello! I'm ready to help with you to create the best set of study guides!" }
  ]);
  
  // State for the input box (string type is inferred, but explicit is cleaner)
  const [input, setInput] = useState<string>("");
  
  // State for loading spinner (boolean type is inferred)
  const [isLoading, setIsLoading] = useState(false);

  // Reference for auto-scrolling
  const messagesEndRef = useRef<HTMLDivElement>(null); // Explicitly type the ref content

  // Function to scroll to bottom
  const scrollToBottom = () => {
    // Optional chaining ensures it only runs if the ref is connected
    messagesEndRef.current?.scrollIntoView({ behavior: "smooth" }); 
  };

  useEffect(scrollToBottom, [messages]);

  // Function to send message to Java
  const sendMessage = async () => {
    if (!input.trim()) return;

    // 1. Add User Message immediately (TypeScript validates this object structure)
    const newUserMessage: ChatMessage = { role: 'user', content: input }; // TS check 
    const newMessages = [...messages, newUserMessage];
    setMessages(newMessages);
    setInput("");
    setIsLoading(true);

    try {
      // 2. Call Spring Boot API
      const response = await fetch('http://localhost:8080/api/ask', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ question: input }),
      });

      const data = await response.json();

      // 3. Add Bot Response (TypeScript validates this object structure)
      const botResponse: ChatMessage = { role: 'bot', content: data.answer }; // TS check
      setMessages((prevMessages) => [...prevMessages, botResponse]);

    } catch (error) {
      console.error("Error:", error);
      const errorMessage: ChatMessage = { role: 'bot', content: "Error: Could not connect to the server." };
      setMessages((prevMessages) => [...prevMessages, errorMessage]);
    } finally {
      setIsLoading(false);
    }
  };


  const STICKER_IMAGES = [ // Array of imported image URLs
    stickerImage1
  ];
  const NUMBER_OF_STICKERS = 15;

  const generateStickers = (side: 'left' | 'right') => {
      return Array.from({ length: NUMBER_OF_STICKERS }).map((_, i) => {
          // Randomly select an image URL from the array
          const imageUrl = STICKER_IMAGES[Math.floor(Math.random() * STICKER_IMAGES.length)];
          
          const style = {
              left: `${Math.random() * 80 + 10}px`, 
              animationDuration: `${Math.random() * 5 + 7}s`, 
              animationDelay: `${Math.random() * 10}s`, 
              // transform: `rotate(${Math.random() * 360}deg)`, // Can remove or keep rotation if desired
          };

          return (
              <div 
                  key={`${side}-${i}`} 
                  className="sticker" 
                  style={style}
              >
                  {/* Now use an <img> tag instead of text */}
                  <img src={imageUrl} alt="Sticker" className="sticker-image" />
              </div>
          );
      });
  
  }

  return (
  <div className="page-wrapper">
      
      {/* STEP 2: Sticker Rain Left (Outside the chat container) */}
      <div className="sticker-rain left">
          {generateStickers('left')}
      </div>
      
      {/* STEP 4: Sticker Rain Right (Outside the chat container) */}
      <div className="sticker-rain right">
          {generateStickers('right')}
      </div>

      <div className="app-container">
        <header className="chat-header">
          <h1>Jarvis Study Assistant</h1>
        </header>

        <div className="chat-window">
          {messages.map((msg, index) => (
            <div key={index} className={`message ${msg.role}`}>
              <div className="bubble">{msg.content}</div>
            </div>
          ))}
          {isLoading && <div className="message bot"><div className="bubble loading">Generating Cheat Sheet...</div></div>}
          <div ref={messagesEndRef} />
        </div>

        <div className="input-area">
          <input 
            type="text" 
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && sendMessage()}
            placeholder="Enter a Topic..." 
            disabled={isLoading}
          />
          <button onClick={sendMessage} disabled={isLoading}>
            Send
          </button>
        </div>
      </div>
    </div> // Closing the single root wrapper


    
  )
}

export default App;