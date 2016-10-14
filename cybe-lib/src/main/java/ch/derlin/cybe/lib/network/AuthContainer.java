package ch.derlin.cybe.lib.network;

/**
 * @author: Lucy Linder
 * @date: 19.06.2014
 */
public interface AuthContainer{
    public String username();
    public String password();

    static class BasicAuthContainer implements AuthContainer{
        private String username, password;


        public BasicAuthContainer( String username, String password ){
            this.username = username;
            this.password = password;
        }


        @Override
        public String username(){
            return username;
        }


        @Override
        public String password(){
            return password;
        }
    }
}
